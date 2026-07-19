package com.example.s3sync.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.s3sync.data.local.AppDatabase
import com.example.s3sync.data.local.S3ConfigManager
import com.example.s3sync.data.local.UploadedFile
import com.example.s3sync.data.remote.S3ClientManager
import com.example.s3sync.util.Logger
import com.example.s3sync.util.LogLevel
import com.example.s3sync.util.S3Scanner
import kotlinx.coroutines.flow.first
import java.io.InputStream

class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val PROGRESS_ACTION = "PROGRESS_ACTION"
        const val MODE_SYNC = "SYNC"
        const val MODE_VERIFY = "VERIFY"
    }

    override suspend fun doWork(): Result {
        val s3ConfigManager = S3ConfigManager(applicationContext)
        val s3ClientManager = S3ClientManager(applicationContext)
        val scanner = S3Scanner(applicationContext)
        val database = AppDatabase.getDatabase(applicationContext)

        val config = s3ConfigManager.s3ConfigFlow.first()
        if (config.bucketName.isEmpty() || config.accessKey.isEmpty()) {
            Logger.log(applicationContext, LogLevel.ERROR, "S3 Config is incomplete. Sync aborted.")
            return Result.failure()
        }

        val month = inputData.getString("TARGET_MONTH") ?: return Result.failure()
        val mode = inputData.getString("WORK_MODE") ?: MODE_SYNC

        Logger.log(applicationContext, LogLevel.INFO, "Starting $mode for month: $month")

        return try {
            if (mode == MODE_SYNC) {
                performSync(month, config, s3ClientManager, scanner, database)
            } else {
                performVerify(month, config, s3ClientManager, scanner)
            }
            // We DON'T clear progress here immediately so the final summary stays visible for a moment
            // The ViewModel will eventually refresh and clear it
            Result.success()
        } catch (e: Exception) {
            Logger.log(applicationContext, LogLevel.ERROR, "Error during $mode for $month: ${e.message}")
            setProgress(workDataOf(PROGRESS_ACTION to "Error: ${e.message}"))
            Result.failure()
        }
    }

    private suspend fun performSync(
        month: String,
        config: com.example.s3sync.data.local.S3Config,
        s3ClientManager: S3ClientManager,
        scanner: S3Scanner,
        database: AppDatabase
    ) {
        val uploadedFileDao = database.uploadedFileDao()
        
        setProgress(workDataOf(PROGRESS_ACTION to "Listing local files..."))
        val localFiles = scanner.getLocalMediaForMonth(month) // Already sorted ASC by dateTaken
        
        setProgress(workDataOf(PROGRESS_ACTION to "Fetching S3 list..."))
        val prefix = if (config.prefix.isEmpty()) "$month/" else "${config.prefix.trimEnd('/')}/$month/"
        val remoteObjects = s3ClientManager.getMetadataMapForPrefix(config, prefix)

        var uploaded = 0
        var skipped = 0
        
        for (file in localFiles) {
            val monthPrefix = "${file.month}/"
            val s3Key = if (config.prefix.isEmpty()) {
                "$monthPrefix${file.name}"
            } else {
                val root = if (config.prefix.endsWith("/")) config.prefix else "${config.prefix}/"
                "$root$monthPrefix${file.name}"
            }

            // Check database first
            val dbFile = uploadedFileDao.getFileByS3Key(s3Key)
            
            // Algorithm: 
            // 1. If in DB, skip.
            // 2. If not in DB, check S3 (remoteObjects).
            // 3. If in S3, add to DB and skip.
            // 4. Otherwise, upload and add to DB.

            if (dbFile != null) {
                skipped++
                // Log v for noise reduction if needed
                continue
            }

            if (remoteObjects.containsKey(s3Key)) {
                // Not in DB but on S3 - populate DB and skip
                val inputStream: InputStream? = applicationContext.contentResolver.openInputStream(file.uri)
                val md5Hex = inputStream?.use { s3ClientManager.calculateMD5Hex(it) } ?: ""
                
                uploadedFileDao.insert(UploadedFile(
                    s3Key = s3Key,
                    fileName = file.name,
                    localUri = file.uri.toString(),
                    fileSize = file.size,
                    md5Hash = md5Hex,
                    uploadTimestamp = System.currentTimeMillis(),
                    dateTaken = file.dateTaken
                ))
                skipped++
                Logger.log(applicationContext, LogLevel.INFO, "Found ${file.name} on S3, updated database.")
                continue
            }

            // Not in DB and not on S3 - Upload
            val result = s3ClientManager.uploadFile(
                config = config,
                key = s3Key,
                uri = file.uri,
                onActionUpdate = { action -> setProgress(workDataOf(PROGRESS_ACTION to action)) }
            )

            if (result == "UPLOADED") {
                val inputStream: InputStream? = applicationContext.contentResolver.openInputStream(file.uri)
                val md5Hex = inputStream?.use { s3ClientManager.calculateMD5Hex(it) } ?: ""

                uploadedFileDao.insert(UploadedFile(
                    s3Key = s3Key,
                    fileName = file.name,
                    localUri = file.uri.toString(),
                    fileSize = file.size,
                    md5Hash = md5Hex,
                    uploadTimestamp = System.currentTimeMillis(),
                    dateTaken = file.dateTaken
                ))
                uploaded++
            } else {
                // Failed - logged by S3ClientManager
            }
        }
        
        val summary = "Sync Finished: $uploaded uploaded, $skipped skipped"
        Logger.log(applicationContext, LogLevel.INFO, "$summary for $month")
        setProgress(workDataOf(PROGRESS_ACTION to summary))
    }

    private suspend fun performVerify(
        month: String,
        config: com.example.s3sync.data.local.S3Config,
        s3ClientManager: S3ClientManager,
        scanner: S3Scanner
    ) {
        setProgress(workDataOf(PROGRESS_ACTION to "Listing local files..."))
        val localFiles = scanner.getLocalMediaForMonth(month)
        
        setProgress(workDataOf(PROGRESS_ACTION to "Fetching S3 metadata..."))
        val prefix = if (config.prefix.isEmpty()) "$month/" else "${config.prefix.trimEnd('/')}/$month/"
        val remoteObjects = s3ClientManager.getMetadataMapForPrefix(config, prefix)

        var matched = 0
        var mismatched = 0
        var missing = 0

        for (file in localFiles) {
            val monthPrefix = "${file.month}/"
            val s3Key = if (config.prefix.isEmpty()) {
                "$monthPrefix${file.name}"
            } else {
                val root = if (config.prefix.endsWith("/")) config.prefix else "${config.prefix}/"
                "$root$monthPrefix${file.name}"
            }

            val remoteEtag = remoteObjects[s3Key]
            if (remoteEtag == null) {
                missing++
                setProgress(workDataOf(PROGRESS_ACTION to "Missing on S3: ${file.name}"))
                Logger.log(applicationContext, LogLevel.WARNING, "Verify: ${file.name} is missing on S3")
                continue
            }

            setProgress(workDataOf(PROGRESS_ACTION to "Verifying: ${file.name}"))
            
            val inputStream: InputStream? = applicationContext.contentResolver.openInputStream(file.uri)
            val localMd5Hex = inputStream?.use { stream ->
                s3ClientManager.calculateMD5Hex(stream)
            }

            if (localMd5Hex == remoteEtag) {
                matched++
                Logger.log(applicationContext, LogLevel.INFO, "Verified: ${file.name} - MD5 match (Local: $localMd5Hex, S3: $remoteEtag)")
            } else {
                mismatched++
                setProgress(workDataOf(PROGRESS_ACTION to "Mismatch: ${file.name}"))
                Logger.log(applicationContext, LogLevel.ERROR, "Verify: ${file.name} MD5 mismatch! Local: $localMd5Hex, S3: $remoteEtag")
            }
        }
        
        val summary = "Verify Finished: $matched matched, $mismatched mismatched, $missing missing"
        Logger.log(applicationContext, LogLevel.INFO, "$summary for $month")
        setProgress(workDataOf(PROGRESS_ACTION to summary))
    }
}
