package com.example.s3sync.data.remote

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.AbortMultipartUploadRequest
import aws.sdk.kotlin.services.s3.model.CompleteMultipartUploadRequest
import aws.sdk.kotlin.services.s3.model.CompletedMultipartUpload
import aws.sdk.kotlin.services.s3.model.CompletedPart
import aws.sdk.kotlin.services.s3.model.CreateMultipartUploadRequest
import aws.sdk.kotlin.services.s3.model.HeadBucketRequest
import aws.sdk.kotlin.services.s3.model.HeadObjectRequest
import aws.sdk.kotlin.services.s3.model.ListObjectsV2Request
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.model.StorageClass
import aws.sdk.kotlin.services.s3.model.UploadPartRequest
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.asByteStream
import com.example.s3sync.data.local.S3Config
import com.example.s3sync.util.Logger
import com.example.s3sync.util.LogLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest

class S3ClientManager(private val context: Context) {
    private val TAG = "S3ClientManager"
    private val MULTIPART_THRESHOLD = 8 * 1024 * 1024L // 8MB
    private val PART_SIZE = 8 * 1024 * 1024L // 8MB

    private class ProgressInputStream(
        private val inputStream: InputStream,
        private val totalSize: Long,
        private val onProgress: (Long) -> Unit
    ) : InputStream() {
        private var bytesRead = 0L
        private var lastUpdate = 0L

        override fun read(): Int {
            val byte = inputStream.read()
            if (byte != -1) {
                bytesRead++
                updateProgress()
            }
            return byte
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = inputStream.read(b, off, len)
            if (read != -1) {
                bytesRead += read
                updateProgress()
            }
            return read
        }

        private fun updateProgress() {
            val now = System.currentTimeMillis()
            // Frequency limit: update at most once every 1000ms to reduce overhead
            if (now - lastUpdate > 1000 || bytesRead == totalSize) {
                onProgress(bytesRead)
                lastUpdate = now
            }
        }

        override fun close() = inputStream.close()
        override fun available(): Int = inputStream.available()
    }

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
            val fileSize = getFileSize(uri) ?: 0L
            if (fileSize > MULTIPART_THRESHOLD) {
                return@withS3Client uploadFileMultipart(config, key, uri, null, onActionUpdate)
            }

            val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                ?: throw Exception("Could not open input stream for $uri")

            // Wrap in BufferedInputStream for better performance
            java.io.BufferedInputStream(inputStream).use { stream ->
                val fileName = key.substringAfterLast("/")

                val progressStream = ProgressInputStream(stream, fileSize) { current ->
                    val percent = if (fileSize > 0) (current * 100 / fileSize).toInt() else 0
                    val totalMb = String.format(java.util.Locale.US, "%.2f", fileSize.toDouble() / (1024 * 1024))
                    val currentMb = String.format(java.util.Locale.US, "%.2f", current.toDouble() / (1024 * 1024))
                    val progressStr = "Uploading: $fileName ($percent% - $currentMb / $totalMb MB)"
                    
                    onActionUpdate?.let {
                        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                        GlobalScope.launch { it(progressStr) }
                    }
                }

                val byteStream = progressStream.asByteStream(fileSize)

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
            val fileSize = getFileSize(uri) ?: 0L
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
            if (fileSize > MULTIPART_THRESHOLD) {
                return@withS3Client uploadFileMultipart(config, key, uri, md5Base64, onActionUpdate)
            }

            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw Exception("Could not open input stream for upload: $uri")

            java.io.BufferedInputStream(inputStream).use { stream ->
                val progressStream = ProgressInputStream(stream, fileSize) { current ->
                    val total = fileSize
                    val percent = if (total > 0) (current * 100 / total).toInt() else 0
                    val totalMb = String.format(java.util.Locale.US, "%.2f", total.toDouble() / (1024 * 1024))
                    val currentMb = String.format(java.util.Locale.US, "%.2f", current.toDouble() / (1024 * 1024))
                    val progressStr = "Uploading: $fileName ($percent% - $currentMb / $totalMb MB)"
                    
                    onActionUpdate?.let {
                        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                        GlobalScope.launch { it(progressStr) }
                    }
                }

                val byteStream = progressStream.asByteStream(fileSize)

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

    private suspend fun uploadFileMultipart(
        config: S3Config,
        key: String,
        uri: Uri,
        md5Base64: String?,
        onActionUpdate: (suspend (String) -> Unit)? = null
    ): String = withS3Client(config) { client ->
        var uploadId: String? = null
        try {
            val fileSize = getFileSize(uri) ?: 0L
            val fileName = key.substringAfterLast("/")

            // 1. Initiate Multipart Upload
            val createRequest = CreateMultipartUploadRequest {
                this.bucket = config.bucketName
                this.key = key
                this.storageClass = StorageClass.DeepArchive
                if (md5Base64 != null) {
                    this.metadata = mapOf("md5-checksum" to md5Base64)
                }
            }
            val createResponse = client.createMultipartUpload(createRequest)
            uploadId = createResponse.uploadId ?: throw Exception("Failed to get uploadId")

            // 2. Upload Parts
            val completedParts = mutableListOf<CompletedPart>()
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw Exception("Could not open input stream for $uri")

            inputStream.use { stream ->
                var bytesUploaded = 0L
                var partNumber = 1
                val buffer = ByteArray(PART_SIZE.toInt())
                var bytesRead: Int

                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    val partData = if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
                    
                    val uploadPartRequest = UploadPartRequest {
                        this.bucket = config.bucketName
                        this.key = key
                        this.uploadId = uploadId
                        this.partNumber = partNumber
                        this.body = ByteStream.fromBytes(partData)
                    }
                    
                    val uploadPartResponse = client.uploadPart(uploadPartRequest)
                    completedParts.add(CompletedPart {
                        this.eTag = uploadPartResponse.eTag
                        this.partNumber = partNumber
                    })

                    bytesUploaded += bytesRead
                    val percent = (bytesUploaded * 100 / fileSize).toInt()
                    val totalMb = String.format(java.util.Locale.US, "%.2f", fileSize.toDouble() / (1024 * 1024))
                    val currentMb = String.format(java.util.Locale.US, "%.2f", bytesUploaded.toDouble() / (1024 * 1024))
                    val progressStr = "Uploading: $fileName ($percent% - $currentMb / $totalMb MB)"
                    
                    onActionUpdate?.let {
                        @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
                        GlobalScope.launch { it(progressStr) }
                    }
                    
                    partNumber++
                }
            }

            // 3. Complete Multipart Upload
            val completeRequest = CompleteMultipartUploadRequest {
                this.bucket = config.bucketName
                this.key = key
                this.uploadId = uploadId
                this.multipartUpload = CompletedMultipartUpload {
                    this.parts = completedParts.sortedBy { it.partNumber }
                }
            }
            client.completeMultipartUpload(completeRequest)

            Logger.log(context, LogLevel.INFO, "Multipart Uploaded: $key")
            "UPLOADED"
        } catch (e: Exception) {
            val errorMsg = "Error in multipart upload for $key: ${e.message}"
            Log.e(TAG, errorMsg, e)
            Logger.log(context, LogLevel.ERROR, errorMsg)

            // Abort if failed
            uploadId?.let { id ->
                try {
                    client.abortMultipartUpload(AbortMultipartUploadRequest {
                        this.bucket = config.bucketName
                        this.key = key
                        this.uploadId = id
                    })
                } catch (ae: Exception) {
                    Log.e(TAG, "Failed to abort multipart upload: ${ae.message}")
                }
            }
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
            
            if (remoteMd5 == localMd5) {
                Logger.log(context, LogLevel.INFO, "File exists: $key (MD5 match: $localMd5)")
                return true
            }
            
            // Fallback: Check ETag if metadata is missing (or if metadata was stored differently)
            val localMd5Hex = base64ToHex(localMd5)
            val eTag = response.eTag?.replace("\"", "")
            
            Log.d(TAG, "Fallback ETag check for $key. Local Hex: $localMd5Hex, Remote ETag: $eTag")
            
            val match = eTag == localMd5Hex
            if (match) {
                Logger.log(context, LogLevel.INFO, "File exists (ETag match): $key (Local MD5 Hex: $localMd5Hex, S3 ETag: $eTag)")
            } else {
                Logger.log(context, LogLevel.WARNING, "File exists but MD5 mismatch: $key (Local: $localMd5Hex, S3 ETag: $eTag)")
            }
            match
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
