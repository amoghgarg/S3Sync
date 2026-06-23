package com.example.s3sync.util

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Context.logDataStore by preferencesDataStore(name = "s3_logs")

enum class LogLevel {
    INFO, WARNING, ERROR
}

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val message: String
) {
    val formattedTime: String
        get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}

object Logger {
    private val LOGS_KEY = stringPreferencesKey("logs")
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private const val MAX_LOGS = 1000

    fun log(context: Context, level: LogLevel, message: String) {
        val entry = "${System.currentTimeMillis()}|${level.name}|$message"
        scope.launch {
            context.logDataStore.edit { preferences ->
                val currentLogs = preferences[LOGS_KEY] ?: ""
                val logsList = currentLogs.split("\n").filter { it.isNotBlank() }.toMutableList()
                logsList.add(0, entry)
                if (logsList.size > MAX_LOGS) {
                    preferences[LOGS_KEY] = logsList.take(MAX_LOGS).joinToString("\n")
                } else {
                    preferences[LOGS_KEY] = logsList.joinToString("\n")
                }
            }
        }
    }

    fun getLogs(context: Context): Flow<List<LogEntry>> {
        return context.logDataStore.data.map { preferences ->
            preferences[LOGS_KEY]?.split("\n")?.filter { it.isNotBlank() }?.mapNotNull { line ->
                val parts = line.split("|", limit = 3)
                if (parts.size == 3) {
                    LogEntry(
                        timestamp = parts[0].toLongOrNull() ?: 0L,
                        level = try { LogLevel.valueOf(parts[1]) } catch (e: Exception) { LogLevel.INFO },
                        message = parts[2]
                    )
                } else null
            } ?: emptyList()
        }
    }

    fun clearLogs(context: Context) {
        scope.launch {
            context.logDataStore.edit { it.clear() }
        }
    }
}
