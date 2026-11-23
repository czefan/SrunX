package io.github.czefan.srunx.service

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import io.github.czefan.srunx.R
import io.github.czefan.srunx.SrunXApp
import io.github.czefan.srunx.protocol.SrunActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 动作型快捷磁贴「SrunX 登录」：
 * 直接在 :bg 进程绑定 Wi-Fi 网卡发起认证请求，并回显状态、图标与短 Toast。
 */
class SrunTileService : TileService() {
    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listeningJob: Job? = null
    private var resetLabelJob: Job? = null
    private var clickInFlight = false
    private var transientNeedsWifi = false

    override fun onStartListening() {
        super.onStartListening()
        val app = application as SrunXApp
        app.coordinator.ensureNetworkCallbackRegistered()
        listeningJob?.cancel()
        listeningJob = tileScope.launch(Dispatchers.Main.immediate) {
            app.coordinator.runtimeState
                .map { Triple(it.wifiConnected, it.lastStatus, it.isBusy) }
                .distinctUntilChanged()
                .collect { (wifiConnected, _, _) ->
                    if (!wifiConnected && transientNeedsWifi) {
                        resetLabelJob?.cancel()
                        resetLabelJob = null
                        transientNeedsWifi = false
                    }
                    if (clickInFlight || resetLabelJob?.isActive == true) {
                        return@collect
                    }
                    updateTileFromState()
                }
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val app = application as SrunXApp
        app.coordinator.ensureNetworkCallbackRegistered()

        if (clickInFlight || app.coordinator.runtimeState.value.isBusy) {
            Toast.makeText(applicationContext, "SrunX：正在认证中…", Toast.LENGTH_SHORT).show()
            return
        }

        resetLabelJob?.cancel()
        resetLabelJob = null
        transientNeedsWifi = false
        clickInFlight = true
        qsTile?.let { tile ->
            tile.state = Tile.STATE_ACTIVE
            tile.icon = Icon.createWithResource(this, R.drawable.ic_srunx_stat)
            tile.label = "认证中…"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                tile.subtitle = "认证中..."
            }
            tile.updateTile()
        }

        tileScope.launch(Dispatchers.Main.immediate) {
            try {
                val result = withContext(Dispatchers.IO) {
                    val cfg = app.repository.configFlow.first()
                    if (cfg.autoMonitorEnabled && !app.coordinator.runtimeState.value.serviceRunning) {
                        runCatching { SrunMonitorService.start(applicationContext) }
                    }
                    app.coordinator.manualLogin(trigger = "快捷磁贴")
                }

                val toastText = if (result.success) {
                    val ip = result.status?.onlineIp?.ifBlank { result.status.clientIp }.orEmpty()
                    if (ip.isNotBlank()) "SrunX：认证成功 ($ip)" else "SrunX：认证成功"
                } else {
                    "SrunX：${result.message}"
                }
                Toast.makeText(applicationContext, toastText, Toast.LENGTH_SHORT).show()

                val state = app.coordinator.runtimeState.value
                val transientLabel = when {
                    result.message.contains("Wi-Fi") -> "未连WiFi"
                    !state.wifiConnected -> null
                    result.success -> "已认证".takeIf { state.lastStatus?.isOnline == true }
                    result.message.contains("填写") -> "请先配置"
                    else -> "认证失败"
                }
                transientNeedsWifi = transientLabel == "已认证"
                updateTileFromState(
                    lastResult = result.takeIf { transientLabel != null },
                    transientLabel = transientLabel,
                )
                if (transientLabel != null) {
                    resetLabelJob = tileScope.launch(Dispatchers.Main) {
                        delay(3_000L)
                        transientNeedsWifi = false
                        resetLabelJob = null
                        updateTileFromState()
                    }
                }
            } finally {
                clickInFlight = false
            }
        }
    }

    override fun onDestroy() {
        resetLabelJob?.cancel()
        resetLabelJob = null
        clickInFlight = false
        transientNeedsWifi = false
        tileScope.cancel()
        super.onDestroy()
    }

    private fun updateTileFromState(
        lastResult: SrunActionResult? = null,
        transientLabel: String? = null,
    ) {
        val tile = qsTile ?: return
        val state = (application as SrunXApp).coordinator.runtimeState.value
        val status = state.lastStatus
        val online = state.wifiConnected && status?.isOnline == true
        tile.state = if (online) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(
            this,
            if (online) R.drawable.ic_srunx_stat else R.drawable.ic_srunx_tile_off,
        )
        tile.label = transientLabel ?: getString(R.string.tile_login_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                state.isBusy -> "认证中..."
                online -> status?.onlineIp?.ifBlank { "已在线" } ?: "已在线"
                lastResult != null && !lastResult.success -> when {
                    lastResult.message.contains("Wi-Fi") -> "未连 Wi-Fi"
                    lastResult.message.contains("填写") -> "请先配置"
                    else -> "认证失败"
                }
                !state.wifiConnected -> "未连 Wi-Fi"
                else -> "点击认证"
            }
        }
        tile.updateTile()
    }
}
