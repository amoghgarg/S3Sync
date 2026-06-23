package com.example.s3sync.data.local

data class S3Config(
    val bucketName: String = "",
    val prefix: String = "",
    val region: String = "us-east-1",
    val accessKey: String = "",
    val secretKey: String = "",
    val storageClass: String = "DEEP_ARCHIVE",
    val syncIntervalHours: Int = 24
)
