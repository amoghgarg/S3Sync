package com.example.s3sync.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface UploadedFileDao {
    @Query("SELECT * FROM uploaded_files WHERE s3Key = :s3Key")
    suspend fun getFileByS3Key(s3Key: String): UploadedFile?

    @Query("SELECT * FROM uploaded_files ORDER BY uploadTimestamp DESC LIMIT 1")
    suspend fun getLastUploadedFile(): UploadedFile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: UploadedFile)

    @Query("SELECT COUNT(*) FROM uploaded_files")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM uploaded_files WHERE s3Key LIKE '%' || :month || '%'")
    suspend fun getCountForMonth(month: String): Int
}
