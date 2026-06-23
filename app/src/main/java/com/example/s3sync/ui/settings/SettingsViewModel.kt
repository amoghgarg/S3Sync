package com.example.s3sync.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.s3sync.data.local.S3Config
import com.example.s3sync.data.local.S3ConfigManager
import com.example.s3sync.data.remote.S3ClientManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val configManager = S3ConfigManager(application)
    private val s3ClientManager = S3ClientManager(application)

    private val _testResult = MutableStateFlow<String?>(null)
    val testResult = _testResult.asStateFlow()

    private val _isTesting = MutableStateFlow(false)
    val isTesting = _isTesting.asStateFlow()

    val s3Config: StateFlow<S3Config> = configManager.s3ConfigFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = S3Config()
        )

    fun saveConfig(config: S3Config) {
        viewModelScope.launch {
            configManager.updateConfig(config)
            // Periodic sync update removed for now as per stateless redesign
        }
    }

    fun testConnection(config: S3Config) {
        viewModelScope.launch {
            _isTesting.value = true
            _testResult.value = null
            try {
                kotlinx.coroutines.withTimeout(15000L) {
                    s3ClientManager.checkConnection(config)
                }
                _testResult.value = "Success: Connection verified"
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                _testResult.value = "Error: Connection timed out (15s). Please check your internet or bucket region."
            } catch (e: Exception) {
                val errorDetail = when {
                    e.message?.contains("SignatureDoesNotMatch") == true -> "Invalid Access/Secret Key"
                    e.message?.contains("NoSuchBucket") == true -> "Bucket not found"
                    e.message?.contains("AccessDenied") == true -> "Access Denied (Check IAM permissions)"
                    else -> e.message ?: e.toString()
                }
                _testResult.value = "Error: $errorDetail"
            } finally {
                _isTesting.value = false
            }
        }
    }
}
