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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
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
    private data class AttemptGuard(
        val autoAttempts: Int = 0,
        val lastAttemptMs: Long = 0L,
        val lastProbeMs: Long = 0L,
    )

    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val authMutex = Mutex()
    private val callbackRegistered = AtomicBoolean(false)

    private val wifiNetworks = ConcurrentHashMap<Long, Network>()
    private val attemptGuards = ConcurrentHashMap<Long, AttemptGuard>()

    private val _runtimeState = MutableStateFlow(SrunRuntimeState())
    val runtimeState: StateFlow<SrunRuntimeState> = _runtimeState.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetworks[network.networkHandle] = network
            updateWifiConnectedState()
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                return
            }
            wifiNetworks[network.networkHandle] = network
            updateWifiConnectedState()

            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                attemptGuards.remove(network.networkHandle)
                return
            }

            if (_runtimeState.value.serviceRunning) {
                scope.launch {
                    autoLoginIfNeeded(network)
                }
            }
        }

        override fun onLost(network: Network) {
            wifiNetworks.remove(network.networkHandle)
            attemptGuards.remove(network.networkHandle)
            updateWifiConnectedState()
        }
    }

    fun ensureNetworkCallbackRegistered() {
        if (!callbackRegistered.compareAndSet(false, true)) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, networkCallback)
        }.onFailure {
            callbackRegistered.set(false)
        }
    }

    fun setServiceRunning(running: Boolean) {
        _runtimeState.update { it.copy(serviceRunning = running) }
        if (running) {
            ensureNetworkCallbackRegistered()
            scope.launch {
                probeCurrentWifiIfNeeded()
            }
        }
    }

    fun probeCurrentWifiIfNeeded() {
        val network = pickActiveWifiNetwork() ?: return
        val caps = cm.getNetworkCapabilities(network) ?: return
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
            scope.launch {
                autoLoginIfNeeded(network)
            }
        }
    }

    suspend fun manualLogin(trigger: String = "手动"): SrunActionResult = authMutex.withLock {
        val config = repository.configFlow.first()
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
                attemptGuards.remove(network.networkHandle)
            }

            val summary = if (result.success) "认证成功: ${result.message}" else result.message
            _runtimeState.update { state ->
                state.copy(
                    isBusy = false,
                    lastStatus = result.status ?: state.lastStatus,
                    lastMessage = summary,
                    lastUpdatedMs = System.currentTimeMillis(),
                )
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

            val summary = if (result.success) "已注销下线" else "注销失败: ${result.message}"
            _runtimeState.update { state ->
                state.copy(
                    isBusy = false,
                    lastStatus = result.status ?: state.lastStatus?.copy(isOnline = false),
                    lastMessage = summary,
                    lastUpdatedMs = System.currentTimeMillis(),
                )
            }
            repository.appendLog(AuthLogEntry(System.currentTimeMillis(), trigger, result.success, summary))
            return result
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
            val statusResult = runCatching { client.getStatus(host) }
            statusResult.onSuccess { status ->
                val summary = if (status.isOnline) {
                    "已在线 · ${status.onlineIp.ifBlank { status.clientIp }}"
                } else {
                    "未在线${status.errorMsg.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()}"
                }
                _runtimeState.update {
                    it.copy(
                        isBusy = false,
                        lastStatus = status,
                        lastMessage = summary,
                        lastUpdatedMs = System.currentTimeMillis(),
                    )
                }
            }.onFailure { err ->
                updateMessage("刷新失败: ${readableError(err)}")
            }
            return statusResult
        } finally {
            setBusy(false)
        }
    }

    private suspend fun autoLoginIfNeeded(network: Network) {
        val config = repository.configFlow.first()
        if (!config.autoMonitorEnabled || !config.isReadyForLogin()) return

        val handle = network.networkHandle
        val now = System.currentTimeMillis()
        val earlyGuard = attemptGuards[handle]
        if (earlyGuard != null) {
            if (earlyGuard.autoAttempts >= MAX_AUTO_ATTEMPTS) return
            if (now - earlyGuard.lastAttemptMs < RETRY_COOLDOWN_MS) return
            if (now - earlyGuard.lastProbeMs < PROBE_COOLDOWN_MS) return
        }

        authMutex.withLock {
            val currentTime = System.currentTimeMillis()
            val guard = attemptGuards[handle] ?: AttemptGuard()
            if (guard.autoAttempts >= MAX_AUTO_ATTEMPTS) return
            if (currentTime - guard.lastAttemptMs < RETRY_COOLDOWN_MS) return
            if (currentTime - guard.lastProbeMs < PROBE_COOLDOWN_MS) return

            val host = config.normalizedHost()
            val probeClient = createWifiClient(network, connectTimeoutMs = 2000, readTimeoutMs = 2500)
            val status = runCatching { probeClient.getStatus(host) }.getOrNull()

            if (status == null || !status.isSrunServer) {
                attemptGuards[handle] = guard.copy(lastProbeMs = currentTime)
                return
            }

            if (status.isOnline) {
                attemptGuards.remove(handle)
                runCatching { cm.reportNetworkConnectivity(network, true) }
                _runtimeState.update {
                    it.copy(
                        lastStatus = status,
                        lastMessage = "已在线 · ${status.onlineIp.ifBlank { status.clientIp }}",
                        lastUpdatedMs = currentTime,
                    )
                }
                return
            }

            attemptGuards[handle] = guard.copy(
                autoAttempts = guard.autoAttempts + 1,
                lastAttemptMs = currentTime,
                lastProbeMs = currentTime,
            )

            setBusy(true)
            try {
                val authClient = createWifiClient(network)
                val result = runCatching { authClient.login(config) }
                    .getOrElse { SrunActionResult(false, readableError(it)) }

                if (result.success) {
                    runCatching { cm.reportNetworkConnectivity(network, true) }
                    attemptGuards.remove(handle)
                }

                val summary = if (result.success) "自动认证成功: ${result.message}" else "自动认证失败: ${result.message}"
                _runtimeState.update { state ->
                    state.copy(
                        isBusy = false,
                        lastStatus = result.status ?: status,
                        lastMessage = summary,
                        lastUpdatedMs = System.currentTimeMillis(),
                    )
                }
                repository.appendLog(AuthLogEntry(System.currentTimeMillis(), "自动保活", result.success, summary))
            } finally {
                setBusy(false)
            }
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

    private fun pickActiveWifiNetwork(): Network? {
        for (net in wifiNetworks.values) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            ) {
                return net
            }
        }
        return null
    }

    private fun updateWifiConnectedState() {
        val connected = pickActiveWifiNetwork() != null
        _runtimeState.update { state ->
            if (state.wifiConnected == connected) state else state.copy(wifiConnected = connected)
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
        private const val MAX_AUTO_ATTEMPTS = 2
        private const val RETRY_COOLDOWN_MS = 45_000L
        private const val PROBE_COOLDOWN_MS = 12_000L
    }
}
