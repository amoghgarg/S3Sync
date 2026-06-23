package com.example.s3sync.data.remote

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.HeadBucketRequest
import aws.sdk.kotlin.services.s3.model.HeadObjectRequest
import aws.sdk.kotlin.services.s3.model.ListObjectsV2Request
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.model.StorageClass
import aws.smithy.kotlin.runtime.content.asByteStream
import com.example.s3sync.data.local.S3Config
import com.example.s3sync.util.Logger
import com.example.s3sync.util.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest

class S3ClientManager(private val context: Context) {
    private val TAG = "S3ClientManager"

    private suspend fun <T> withS3Client(config: S3Config, block: suspend (S3Client) -> T): T = withContext(Dispatchers.IO) {
        val client = S3Client {
            region = config.region
            credentialsProvider = StaticCredentialsProvider {
                accessKeyId = config.accessKey
                secretAccessKey = config.secretKey
            }
        }
        try {
            block(client)
        } finally {
            client.close()
        }
    }

    suspend fun uploadFileBasic(
        config: S3Config,
        key: String,
        uri: Uri,
        onActionUpdate: (suspend (String) -> Unit)? = null
    ): String = withS3Client(config) { client ->
        try {
            val fileSize = getFileSize(uri)
            val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                ?: throw Exception("Could not open input stream for $uri")

            inputStream.use { stream ->
                val fileName = key.substringAfterLast("/")
                onActionUpdate?.invoke("Uploading: $fileName")

                val byteStream = stream.asByteStream(fileSize)

                val request = PutObjectRequest {
                    this.bucket = config.bucketName
                    this.key = key
                    this.body = byteStream
                    this.storageClass = StorageClass.DeepArchive
                }

                client.putObject(request)
                Logger.log(context, LogLevel.INFO, "Uploaded: $key")
                "UPLOADED"
            }
        } catch (e: Exception) {
            val errorMsg = "Error uploading $key: ${e.message} (Cause: ${e.cause?.message ?: "Unknown"})"
            Log.e(TAG, errorMsg, e)
            Logger.log(context, LogLevel.ERROR, errorMsg)
            "FAILED"
        }
    }

    suspend fun uploadFile(
        config: S3Config,
        key: String,
        uri: Uri,
        onActionUpdate: (suspend (String) -> Unit)? = null
    ): String = withS3Client(config) { client ->
        try {
            val fileSize = getFileSize(uri)
            val fileName = key.substringAfterLast("/")

            // 1. Calculate MD5
            onActionUpdate?.invoke("Calculating MD5: $fileName")
            val md5Base64 = context.contentResolver.openInputStream(uri)?.use { 
                calculateMD5(it) 
            } ?: throw Exception("Could not open input stream for MD5 calculation: $uri")

            // 2. Check if file already exists with same MD5 using Metadata
            onActionUpdate?.invoke("Checking: $fileName")
            if (checkIfFileExists(client, config.bucketName, key, md5Base64)) {
                Log.d(TAG, "Skipping upload, MD5 match for: $key")
                return@withS3Client "SKIPPED"
            }

            // 3. Upload if not exists or hash mismatch
            onActionUpdate?.invoke("Uploading: $fileName")
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw Exception("Could not open input stream for upload: $uri")

            inputStream.use { stream ->
                val byteStream = stream.asByteStream(fileSize)

                val request = PutObjectRequest {
                    this.bucket = config.bucketName
                    this.key = key
                    this.body = byteStream
                    this.contentMd5 = md5Base64
                    this.metadata = mapOf("md5-checksum" to md5Base64)
                    this.storageClass = StorageClass.DeepArchive
                }

                client.putObject(request)
                Logger.log(context, LogLevel.INFO, "Uploaded: $key (MD5: $md5Base64)")
                "UPLOADED"
            }
        } catch (e: Exception) {
            val errorMsg = "Error uploading $key: ${e.message} (Cause: ${e.cause?.message ?: "Unknown"})"
            Log.e(TAG, errorMsg, e)
            Logger.log(context, LogLevel.ERROR, errorMsg)
            "FAILED"
        }
    }

    private fun getFileSize(uri: Uri): Long? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex != -1) cursor.getLong(sizeIndex) else null
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file size for $uri", e)
            null
        }
    }

    /**
     * Optimized sync: Fetch all metadata for a prefix once to avoid hundreds of HeadObject calls.
     */
    suspend fun getMetadataMapForPrefix(config: S3Config, prefix: String): Map<String, String> = withS3Client(config) { client ->
        try {
            val metadataMap = mutableMapOf<String, String>()
            var isTruncated = true
            var nextContinuationToken: String? = null

            while (isTruncated) {
                val request = ListObjectsV2Request {
                    bucket = config.bucketName
                    this.prefix = prefix
                    continuationToken = nextContinuationToken
                }
                
                val response = client.listObjectsV2(request)
                
                response.contents?.forEach { obj ->
                    val key = obj.key
                    val etag = obj.eTag?.replace("\"", "")
                    if (key != null && etag != null) {
                        metadataMap[key] = etag
                    }
                }
                
                isTruncated = response.isTruncated ?: false
                nextContinuationToken = response.nextContinuationToken
            }
            metadataMap
        } catch (e: Exception) {
            Logger.log(context, LogLevel.ERROR, "Failed to fetch bulk metadata for $prefix: ${e.message}")
            emptyMap()
        }
    }

    private suspend fun checkIfFileExists(client: S3Client, bucket: String, key: String, localMd5: String): Boolean {
        return try {
            val headRequest = HeadObjectRequest {
                this.bucket = bucket
                this.key = key
            }
            val response = client.headObject(headRequest)
            val remoteMd5 = response.metadata?.get("md5-checksum")
            
            Log.d(TAG, "Comparing MD5 for $key. Local: $localMd5, Remote Metadata: $remoteMd5")
            
            if (remoteMd5 == localMd5) return true
            
            // Fallback: Check ETag if metadata is missing (or if metadata was stored differently)
            val localMd5Hex = base64ToHex(localMd5)
            val eTag = response.eTag?.replace("\"", "")
            
            Log.d(TAG, "Fallback ETag check for $key. Local Hex: $localMd5Hex, Remote ETag: $eTag")
            
            eTag == localMd5Hex
        } catch (e: Exception) {
            Log.d(TAG, "File does not exist on S3: $key. Exception: ${e.message}" )
            false
        }
    }

    private fun base64ToHex(base64: String): String {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun calculateMD5(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        val hash = digest.digest()
        return Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    fun calculateMD5Hex(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        val hash = digest.digest()
        return hash.joinToString("") { "%02x".format(it) }
    }

    suspend fun checkConnection(config: S3Config): Boolean = withS3Client(config) { client ->
        try {
            val request = HeadBucketRequest {
                bucket = config.bucketName
            }
            client.headBucket(request)
            true
        } catch (e: Exception) {
            val errorMsg = "S3 Connection Check Failed: ${e.message} (Cause: ${e.cause?.message ?: "Unknown"})"
            Log.e(TAG, errorMsg, e)
            Logger.log(context, LogLevel.ERROR, errorMsg)
            throw Exception(errorMsg, e)
        }
    }

    suspend fun listObjects(config: S3Config, prefix: String): List<String> = withS3Client(config) { client ->
        try {
            Log.d(TAG, "Listing objects for prefix: $prefix in bucket: ${config.bucketName}")
            val request = ListObjectsV2Request {
                bucket = config.bucketName
                this.prefix = prefix
            }
            val response = client.listObjectsV2(request)
            val keys = response.contents?.mapNotNull { it.key } ?: emptyList()
            Log.d(TAG, "Found ${keys.size} objects for prefix: $prefix")
            keys
        } catch (e: Exception) {
            val errorMsg = "Error listing objects in S3 ($prefix): ${e.message} (Cause: ${e.cause?.message ?: "Unknown"})"
            Log.e(TAG, errorMsg, e)
            Logger.log(context, LogLevel.ERROR, errorMsg)
            emptyList()
        }
    }
}
