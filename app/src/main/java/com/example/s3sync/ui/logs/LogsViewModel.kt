package com.example.s3sync.ui.logs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.s3sync.util.LogEntry
import com.example.s3sync.util.LogLevel
import com.example.s3sync.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class LogsViewModel(application: Application) : AndroidViewModel(application) {
    private val _activeFilters = MutableStateFlow<Set<LogLevel>>(emptySet())
    val activeFilters = _activeFilters.asStateFlow()

    val logs: StateFlow<List<LogEntry>> = Logger.getLogs(application)
        .combine(_activeFilters) { logList, filters ->
            if (filters.isEmpty()) logList else logList.filter { it.level in filters }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun toggleFilter(level: LogLevel) {
        val current = _activeFilters.value
        _activeFilters.value = if (level in current) {
            current - level
        } else {
            current + level
        }
    }

    fun clearFilters() {
        _activeFilters.value = emptySet()
    }

    fun clearLogs() {
        Logger.clearLogs(getApplication())
    }
}
