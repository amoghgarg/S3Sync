package com.example.s3sync.util

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.example.s3sync.data.remote.S3ClientManager
import com.example.s3sync.data.local.S3Config
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LocalMedia(
    val uri: android.net.Uri,
    val name: String,
    val size: Long,
    val month: String,
    val dateTaken: Long
)

class S3Scanner(private val context: Context) {

    fun getLocalMediaForMonth(month: String): List<LocalMedia> {
        val assets = mutableListOf<LocalMedia>()
        val monthFormat = SimpleDateFormat("yyyy-MM", Locale.US)

        val imageProjection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN
        )

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            imageProjection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_TAKEN} ASC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val dateTaken = cursor.getLong(dateColumn)
                val assetMonth = monthFormat.format(Date(dateTaken))
                
                if (assetMonth == month) {
                    assets.add(LocalMedia(
                        uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        name = cursor.getString(nameColumn),
                        size = cursor.getLong(sizeColumn),
                        month = assetMonth,
                        dateTaken = dateTaken
                    ))
                }
            }
        }

        val videoProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_TAKEN
        )

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            videoProjection,
            null,
            null,
            "${MediaStore.Video.Media.DATE_TAKEN} ASC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val dateTaken = cursor.getLong(dateColumn)
                val assetMonth = monthFormat.format(Date(dateTaken))

                if (assetMonth == month) {
                    assets.add(LocalMedia(
                        uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        name = cursor.getString(nameColumn),
                        size = cursor.getLong(sizeColumn),
                        month = assetMonth,
                        dateTaken = dateTaken
                    ))
                }
            }
        }
        return assets.sortedBy { it.dateTaken }
    }

    suspend fun getS3FileCount(s3ClientManager: S3ClientManager, config: S3Config, month: String): Int {
        val prefix = if (config.prefix.isEmpty()) "$month/" else "${config.prefix.trimEnd('/')}/$month/"
        return s3ClientManager.listObjects(config, prefix).size
    }
}
