package com.example.s3sync.ui.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.s3sync.data.local.AppDatabase
import com.example.s3sync.data.local.S3Config
import com.example.s3sync.data.local.S3ConfigManager
import com.example.s3sync.data.remote.S3ClientManager
import com.example.s3sync.util.S3Scanner
import com.example.s3sync.worker.SyncScheduler
import com.example.s3sync.worker.SyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class MonthState(
    val monthKey: String,
    val s3Count: Int = 0,
    val dbCount: Int = 0,
    val localCount: Int = 0,
    val lastUpdated: Long = 0L,
    val isSyncing: Boolean = false,
    val currentFileAction: String? = null,
    val error: String? = null
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {
    private val s3ClientManager = S3ClientManager(application)
    private val s3ConfigManager = S3ConfigManager(application)
    private val scanner = S3Scanner(application)
    private val workManager = WorkManager.getInstance(application)
    private val database = AppDatabase.getDatabase(application)
    private val uploadedFileDao = database.uploadedFileDao()

    private val _monthStates = MutableStateFlow<Map<String, MonthState>>(emptyMap())
    val monthStates = _monthStates.asStateFlow()

    val s3Config: StateFlow<S3Config> = s3ConfigManager.s3ConfigFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = S3Config()
        )

    init {
        initializeMonths()
        // refreshAll() // Removed to avoid automatic sync/verify on navigation
        observeWorkProgress()
    }

    private fun initializeMonths() {
        val monthFormat = SimpleDateFormat("yyyy-MM", Locale.US)
        val calendar = Calendar.getInstance()
        val months = mutableMapOf<String, MonthState>()
        for (i in 0..5) {
            val key = monthFormat.format(calendar.time)
            viewModelScope.launch {
                val dbUploaded = uploadedFileDao.getCountForMonth(key)
                updateMonthState(key) { it.copy(
                    localCount = scanner.getLocalMediaForMonth(key).size,
                    dbCount = dbUploaded
                )}
            }
            months[key] = MonthState(monthKey = key)
            calendar.add(Calendar.MONTH, -1)
        }
        _monthStates.value = months
    }

    private fun observeWorkProgress() {
        _monthStates.value.keys.forEach { month ->
            viewModelScope.launch {
                // Combine progress from both Sync and Verify
                val syncFlow = workManager.getWorkInfosForUniqueWorkFlow("MANUAL_SYNC_$month")
                val verifyFlow = workManager.getWorkInfosForUniqueWorkFlow("VERIFY_$month")
                
                kotlinx.coroutines.flow.combine(syncFlow, verifyFlow) { syncInfos, verifyInfos ->
                    val syncInfo = syncInfos.firstOrNull()
                    val verifyInfo = verifyInfos.firstOrNull()
                    
                    // Prioritize whichever is running
                    val activeInfo = listOf(syncInfo, verifyInfo).find { 
                        it?.state == WorkInfo.State.RUNNING || it?.state == WorkInfo.State.ENQUEUED 
                    } ?: syncInfo ?: verifyInfo

                    activeInfo
                }.collect { workInfo ->
                    if (workInfo != null) {
                        val progress = workInfo.progress.getString(SyncWorker.PROGRESS_ACTION)
                        val isRunning = workInfo.state == WorkInfo.State.RUNNING || 
                                       workInfo.state == WorkInfo.State.ENQUEUED
                        
                        updateMonthState(month) { it.copy(
                            isSyncing = isRunning,
                            currentFileAction = if (isRunning) progress else null
                        )}
                        
                        if (workInfo.state == WorkInfo.State.SUCCEEDED) {
                            refreshMonth(month)
                        }
                    }
                }
            }
        }
    }

    fun refreshAll() {
        _monthStates.value.keys.forEach { refreshMonth(it) }
    }

    private fun refreshMonth(month: String) {
        viewModelScope.launch {
            val config = s3Config.value
            if (config.bucketName.isEmpty()) return@launch

            updateMonthState(month) { it.copy(isSyncing = true, error = null) }
            try {
                val count = scanner.getS3FileCount(s3ClientManager, config, month)
                val dbUploaded = uploadedFileDao.getCountForMonth(month)
                updateMonthState(month) { it.copy(
                    s3Count = count,
                    dbCount = dbUploaded,
                    lastUpdated = System.currentTimeMillis(),
                    isSyncing = false
                )}
            } catch (e: Exception) {
                updateMonthState(month) { it.copy(
                    error = "Failed to fetch S3 count: ${e.message}",
                    isSyncing = false
                )}
            }
        }
    }

    private fun updateMonthState(month: String, transform: (MonthState) -> MonthState) {
        val current = _monthStates.value.toMutableMap()
        val state = current[month] ?: MonthState(month)
        current[month] = transform(state)
        _monthStates.value = current
    }

    fun onSyncNowClicked(month: String) {
        SyncScheduler.triggerManualSync(getApplication(), month)
    }

    fun onVerifyClicked(month: String) {
        SyncScheduler.triggerVerify(getApplication(), month)
    }

    fun onSuspendAllClicked() {
        SyncScheduler.stopAllSyncs(getApplication())
    }
}
