package io.github.czefan.srunx.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import io.github.czefan.srunx.protocol.AuthLogEntry
import io.github.czefan.srunx.protocol.SrunActionResult
import io.github.czefan.srunx.protocol.SrunClient
import io.github.czefan.srunx.protocol.SrunConfig
import io.github.czefan.srunx.protocol.SrunStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean

data class SrunRuntimeState(
    val isBusy: Boolean = false,
    val serviceRunning: Boolean = false,
    val wifiConnected: Boolean = false,
    val lastStatus: SrunStatus? = null,
    val lastMessage: String = "等待连接校园网",
    val lastUpdatedMs: Long = 0L,
)

/**
 * 统一协调 Wi-Fi 网卡绑定、自动重登防抖、在线状态刷新与本地日志记录。
 */
class AuthCoordinator(
    private val context: Context,
    private val repository: SettingsRepository,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authMutex = Mutex()
    private val callbackRegistered = AtomicBoolean(false)

    private val networkLock = Any()
    // null 表示已收到 onAvailable，等待首次能力回调。
    private val wifiNetworks = linkedMapOf<Network, Boolean?>()
    private val submittedJobs = mutableMapOf<Network, Job>()
    private val autoJobs = mutableMapOf<Network, Job>()
    private var statusNetwork: Network? = null

    private val _runtimeState = MutableStateFlow(SrunRuntimeState())
    val runtimeState: StateFlow<SrunRuntimeState> = _runtimeState.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(networkLock) {
                wifiNetworks[network] = null
                _runtimeState.update { it.copy(wifiConnected = true) }
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                synchronized(networkLock) {
                    if (network in wifiNetworks) {
                        removeWifiNetworkLocked(network)
                    }
                }
                return
            }
            synchronized(networkLock) {
                if (network !in wifiNetworks) return
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                wifiNetworks[network] = validated
                if (validated) {
                    val job = autoJobs[network]
                    if (job != null && submittedJobs[network] !== job) {
                        autoJobs.remove(network)?.cancel()
                    }
                } else {
                    scheduleAutoLogin(network)
                }
            }
        }

        override fun onLost(network: Network) {
            synchronized(networkLock) {
                removeWifiNetworkLocked(network)
            }
        }
    }

    private fun removeWifiNetworkLocked(network: Network) {
        wifiNetworks.remove(network)
        autoJobs.remove(network)?.cancel()
        val connected = wifiNetworks.isNotEmpty()
        val lostStatus = statusNetwork == network
        if (lostStatus) statusNetwork = null
        _runtimeState.update {
            if (lostStatus) {
                it.copy(
                    wifiConnected = connected,
                    lastStatus = null,
                    lastMessage = if (connected) "等待确认认证状态" else "当前未连接 Wi-Fi",
                    lastUpdatedMs = System.currentTimeMillis(),
                )
            } else {
                it.copy(wifiConnected = connected)
            }
        }
    }

    fun ensureNetworkCallbackRegistered() {
        if (!callbackRegistered.compareAndSet(false, true)) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, networkCallback)
        }.onFailure {
            callbackRegistered.set(false)
        }
    }

    fun setServiceRunning(running: Boolean) {
        synchronized(networkLock) {
            _runtimeState.update { it.copy(serviceRunning = running) }
            if (running) {
                wifiNetworks.keys.forEach { scheduleAutoLogin(it) }
            } else {
                cancelAutoJobs()
            }
        }
        if (running) ensureNetworkCallbackRegistered()
    }

    private fun scheduleAutoLogin(network: Network) {
        if (!_runtimeState.value.serviceRunning || wifiNetworks[network] != false || network in autoJobs) return

        // 保留完成的任务，避免同一段未验证连接反复触发认证。
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val retryAfterMs = autoLoginAttempt(network) ?: return@launch
            delay(retryAfterMs)
            if (synchronized(networkLock) { wifiNetworks[network] == false }) autoLoginAttempt(network)
        }
        job.invokeOnCompletion {
            synchronized(networkLock) {
                if (submittedJobs[network] === job) submittedJobs.remove(network)
                if (autoJobs[network] === job && wifiNetworks[network] == true) autoJobs.remove(network)
            }
        }
        autoJobs[network] = job
        job.start()
    }

    fun onConfigSaved(scheduleLogin: Boolean = true) {
        synchronized(networkLock) {
            if (scheduleLogin) {
                cancelAutoJobs()
                wifiNetworks.keys.forEach { scheduleAutoLogin(it) }
            } else {
                // 手动登录接管当前未验证连接，保留任务记录以合并重复回调。
                autoJobs.values.toList().forEach { it.cancel() }
            }
        }
    }

    private fun cancelAutoJobs() {
        val jobs = autoJobs.values.toList()
        autoJobs.clear()
        jobs.forEach { it.cancel() }
    }

    suspend fun manualLogin(trigger: String = "手动", draft: SrunConfig? = null): SrunActionResult = authMutex.withLock {
        val config = draft ?: repository.configFlow.first()
        if (!config.isReadyForLogin()) {
            val msg = "请先填写完整的网关地址、学号与密码"
            updateMessage(msg)
            return SrunActionResult(false, msg)
        }

        val network = pickActiveWifiNetwork()
        if (network == null) {
            val msg = "当前未连接 Wi-Fi，请先连接校园网"
            updateMessage(msg)
            repository.appendLog(AuthLogEntry(System.currentTimeMillis(), trigger, false, msg))
            return SrunActionResult(false, msg)
        }

        setBusy(true)
        try {
            val client = createWifiClient(network)
            val result = runCatching { client.login(config) }
                .getOrElse { SrunActionResult(false, readableError(it)) }

            if (result.success) {
                runCatching { cm.reportNetworkConnectivity(network, true) }
                synchronized(networkLock) { autoJobs[network]?.cancel() }
            }

            val summary = if (result.success) "认证成功: ${result.message}" else result.message
            publishNetworkResult(network, summary) { previous ->
                result.status ?: if (result.success && previous?.isOnline != true) {
                    SrunStatus(isOnline = true, isSrunServer = true)
                } else {
                    previous
                }
            }
            repository.appendLog(AuthLogEntry(System.currentTimeMillis(), trigger, result.success, summary))
            return result
        } finally {
            setBusy(false)
        }
    }


    suspend fun manualLogout(trigger: String = "手动"): SrunActionResult = authMutex.withLock {
        val config = repository.configFlow.first()
        if (config.normalizedHost().isEmpty() || config.username.isBlank()) {
            val msg = "请先填写网关地址与学号"
            updateMessage(msg)
            return SrunActionResult(false, msg)
        }

        val network = pickActiveWifiNetwork()
        if (network == null) {
            val msg = "当前未连接 Wi-Fi"
            updateMessage(msg)
            return SrunActionResult(false, msg)
        }

        setBusy(true)
        try {
            val client = createWifiClient(network)
            val result = runCatching { client.logout(config) }
                .getOrElse { SrunActionResult(false, readableError(it)) }

            val summary = when {
                result.success -> "已注销下线"
                result.status != null -> "注销失败: ${result.message}"
                else -> "注销结果未确认: ${result.message}"
            }
            publishNetworkResult(network, summary) { previous ->
                result.status ?: if (result.success) SrunStatus(isOnline = false) else previous
            }
            repository.appendLog(AuthLogEntry(System.currentTimeMillis(), trigger, result.success, summary))
            return if (result.success) result else result.copy(message = summary)
        } finally {
            setBusy(false)
        }
    }

    suspend fun refreshStatus(): Result<SrunStatus> = authMutex.withLock {
        val config = repository.configFlow.first()
        val host = config.normalizedHost()
        if (host.isEmpty()) {
            val msg = "请先配置认证网关地址"
            updateMessage(msg)
            return Result.failure(IllegalStateException(msg))
        }

        val network = pickActiveWifiNetwork()
        if (network == null) {
            val msg = "当前未连接 Wi-Fi"
            updateMessage(msg)
            return Result.failure(IllegalStateException(msg))
        }

        setBusy(true)
        try {
            val client = createWifiClient(network)
            val statusResult = runCatching {
                client.getStatus(host).also {
                    check(it.isSrunServer) { "未识别到深澜认证网关" }
                }
            }
            statusResult.onSuccess { status ->
                val summary = if (status.isOnline) {
                    "已在线 · ${status.onlineIp.ifBlank { status.clientIp }}"
                } else {
                    "未在线${status.errorMsg.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}"
                }
                publishNetworkResult(network, summary) { status }
            }.onFailure { err ->
                publishNetworkResult(network, "刷新失败: ${readableError(err)}") { it }
            }
            return statusResult
        } finally {
            setBusy(false)
        }
    }

    private suspend fun autoLoginAttempt(network: Network): Long? = authMutex.withLock {
        val config = repository.configFlow.first()
        if (!config.autoMonitorEnabled || !config.isReadyForLogin()) return null

        currentCoroutineContext().ensureActive()
        val probeClient = createWifiClient(network, connectTimeoutMs = 2000, readTimeoutMs = 2500)
        val status = runCatching { probeClient.getStatus(config.normalizedHost()) }.getOrNull()
        if (status == null || !status.isSrunServer) return PROBE_RETRY_DELAY_MS

        currentCoroutineContext().ensureActive()
        if (status.isOnline) {
            publishNetworkResult(network, "已在线 · ${status.onlineIp.ifBlank { status.clientIp }}") { status }
            runCatching { cm.reportNetworkConnectivity(network, true) }
            return null
        }

        currentCoroutineContext().ensureActive()
        setBusy(true)
        try {
            val authClient = createWifiClient(network)
            val task = currentCoroutineContext()
            val job = checkNotNull(task[Job])
            val result = runCatching {
                authClient.login(config) {
                    synchronized(networkLock) {
                        task.ensureActive()
                        submittedJobs[network] = job
                    }
                }
            }
                .getOrElse { SrunActionResult(false, readableError(it)) }

            task.ensureActive()
            val summary = if (result.success) "自动认证成功: ${result.message}" else "自动认证失败: ${result.message}"
            publishNetworkResult(network, summary) {
                result.status ?: status.copy(isOnline = result.success)
            }
            if (result.success) {
                runCatching { cm.reportNetworkConnectivity(network, true) }
            }
            repository.appendLog(AuthLogEntry(System.currentTimeMillis(), "自动保活", result.success, summary))
            synchronized(networkLock) {
                if (submittedJobs[network] === job) submittedJobs.remove(network)
            }
            if (result.success || synchronized(networkLock) { wifiNetworks[network] != false }) null else AUTH_RETRY_DELAY_MS
        } finally {
            setBusy(false)
        }
    }

    private fun createWifiClient(
        network: Network,
        connectTimeoutMs: Int = 2500,
        readTimeoutMs: Int = 4000,
    ): SrunClient = SrunClient(
        connectionFactory = { url -> network.openConnection(url) as HttpURLConnection },
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs,
    )

    private fun pickActiveWifiNetwork(): Network? = synchronized(networkLock) {
        statusNetwork ?: wifiNetworks.keys.firstOrNull()
    }

    private fun publishNetworkResult(
        network: Network,
        message: String,
        status: (SrunStatus?) -> SrunStatus?,
    ) {
        synchronized(networkLock) {
            if (network !in wifiNetworks) return
            val previous = _runtimeState.value.lastStatus.takeIf { statusNetwork == network }
            statusNetwork = network
            _runtimeState.update {
                it.copy(
                    lastStatus = status(previous),
                    lastMessage = message,
                    lastUpdatedMs = System.currentTimeMillis(),
                )
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        _runtimeState.update { it.copy(isBusy = busy) }
    }

    private fun updateMessage(msg: String) {
        _runtimeState.update { it.copy(lastMessage = msg, lastUpdatedMs = System.currentTimeMillis()) }
    }

    private fun readableError(err: Throwable): String {
        val msg = err.message?.trim().orEmpty()
        return msg.ifEmpty { err.javaClass.simpleName }
    }

    companion object {
        private const val AUTH_RETRY_DELAY_MS = 45_000L
        private const val PROBE_RETRY_DELAY_MS = 12_000L
    }
}
