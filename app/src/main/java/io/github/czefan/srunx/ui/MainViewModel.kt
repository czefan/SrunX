package io.github.czefan.srunx.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.github.czefan.srunx.data.SrunRuntimeState
import io.github.czefan.srunx.protocol.AuthLogEntry
import io.github.czefan.srunx.protocol.SrunConfig
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class SrunUiState(
    val config: SrunConfig = SrunConfig(),
    val runtime: SrunRuntimeState = SrunRuntimeState(),
    val logs: List<AuthLogEntry> = emptyList(),
    val configLoaded: Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val bgClient = BgServiceClient(application)

    val uiState: StateFlow<SrunUiState> = bgClient.uiState
    val events: SharedFlow<String> = bgClient.events

    init {
        bgClient.bind()
    }

    override fun onCleared() {
        bgClient.unbind()
        super.onCleared()
    }

    fun saveConfig(
        host: String,
        acId: String,
        username: String,
        password: String,
        domainSuffix: String,
    ) {
        val current = uiState.value.config
        bgClient.saveConfig(
            current.copy(
                host = host,
                acId = acId,
                username = username,
                password = password,
                domainSuffix = domainSuffix,
            ),
        )
    }

    fun loginNow(draft: SrunConfig?) = bgClient.loginNow(draft)

    fun logoutNow() = bgClient.logoutNow()

    fun refreshStatus() = bgClient.refreshStatus()

    fun setAutoMonitor(enabled: Boolean) = bgClient.setAutoMonitor(enabled)

    fun setAutoStartOnBoot(enabled: Boolean) = bgClient.setAutoStartOnBoot(enabled)

    fun clearLogs() = bgClient.clearLogs()
}
