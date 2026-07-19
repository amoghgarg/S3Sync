// /app/src/main/java/com/example/s3sync/data/remote/S3ClientManager.kt

package com.example.s3sync.data.remote

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.HeadBucketRequest
import aws.sdk.kotlin.services.s3.model.ListObjectsV2Request
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.model.StorageClass
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

    suspend fun uploadFile(
        config: S3Config,
        key: String,
        uri: Uri,
        onActionUpdate: (suspend (String) -> Unit)? = null
    ): String = withS3Client(config) { client ->
        try {
            val fileSize = getFileSize(uri) ?: 0L
            val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                ?: throw Exception("Could not open input stream for $uri")

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
            val errorMsg = "Error uploading $key: ${e.message}"
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
            null
        }
    }

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
            client.headBucket(HeadBucketRequest {
                this.bucket = config.bucketName
            })
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun listObjects(config: S3Config, prefix: String): List<String> = withS3Client(config) { client ->
        try {
            val request = ListObjectsV2Request {
                bucket = config.bucketName
                this.prefix = prefix
            }
            val response = client.listObjectsV2(request)
            response.contents?.mapNotNull { it.key } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}