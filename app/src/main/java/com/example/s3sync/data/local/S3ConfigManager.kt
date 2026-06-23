package com.example.s3sync.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "s3_configs")

class S3ConfigManager(private val context: Context) {
    companion object {
        val BUCKET_NAME = stringPreferencesKey("bucket_name")
        val PREFIX = stringPreferencesKey("prefix")
        val REGION = stringPreferencesKey("region")
        val ACCESS_KEY = stringPreferencesKey("access_key")
        val SECRET_KEY = stringPreferencesKey("secret_key")
        val STORAGE_CLASS = stringPreferencesKey("storage_class")
        val SYNC_INTERVAL = intPreferencesKey("sync_interval")
    }

    val s3ConfigFlow: Flow<S3Config> = context.dataStore.data.map { preferences ->
        S3Config(
            bucketName = preferences[BUCKET_NAME] ?: "",
            prefix = preferences[PREFIX] ?: "",
            region = preferences[REGION] ?: "us-east-1",
            accessKey = preferences[ACCESS_KEY] ?: "",
            secretKey = preferences[SECRET_KEY] ?: "",
            storageClass = preferences[STORAGE_CLASS] ?: "DEEP_ARCHIVE",
            syncIntervalHours = preferences[SYNC_INTERVAL] ?: 24
        )
    }

    suspend fun updateConfig(config: S3Config) {
        context.dataStore.edit { preferences ->
            preferences[BUCKET_NAME] = config.bucketName
            preferences[PREFIX] = config.prefix
            preferences[REGION] = config.region
            preferences[ACCESS_KEY] = config.accessKey
            preferences[SECRET_KEY] = config.secretKey
            preferences[STORAGE_CLASS] = config.storageClass
            preferences[SYNC_INTERVAL] = config.syncIntervalHours
        }
    }
}
