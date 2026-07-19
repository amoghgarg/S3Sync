package com.example.s3sync.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "uploaded_files")
data class UploadedFile(
    @PrimaryKey val s3Key: String,
    val fileName: String,
    val localUri: String,
    val fileSize: Long,
    val md5Hash: String,
    val uploadTimestamp: Long,
    val dateTaken: Long
)
