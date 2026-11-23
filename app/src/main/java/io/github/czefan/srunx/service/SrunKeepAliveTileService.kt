package io.github.czefan.srunx.service

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import io.github.czefan.srunx.R
import io.github.czefan.srunx.SrunXApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 开关型快捷磁贴「SrunX 保活」：
 * 真实反映 :bg 后台服务存活状态，点击仅启停 SrunMonitorService 并同步自动监听开关。
 */
class SrunKeepAliveTileService : TileService() {
    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var resetLabelJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        if (resetLabelJob?.isActive == true) return
        val running = (application as SrunXApp).coordinator.runtimeState.value.serviceRunning
        updateTile(running = running)
    }

    override fun onClick() {
        super.onClick()
        resetLabelJob?.cancel()
        val app = application as SrunXApp
        val currentlyRunning = app.coordinator.runtimeState.value.serviceRunning

        if (!currentlyRunning) {
            updateTile(running = true, transientLabel = "已开启")
            tileScope.launch {
                app.repository.setAutoMonitor(true)
                val started = runCatching { SrunMonitorService.start(applicationContext) }
                withContext(Dispatchers.Main) {
                    if (started.isSuccess) {
                        Toast.makeText(applicationContext, "SrunX：后台保活已开启", Toast.LENGTH_SHORT).show()
                        scheduleLabelReset()
                    } else {
                        Toast.makeText(applicationContext, "SrunX：启动保活失败", Toast.LENGTH_SHORT).show()
                        updateTile(running = false, transientLabel = "启动失败")
                        scheduleLabelReset()
                    }
                }
            }
        } else {
            updateTile(running = false, transientLabel = "已关闭")
            tileScope.launch {
                app.repository.setAutoMonitor(false)
                SrunMonitorService.stop(applicationContext)
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "SrunX：后台保活已关闭", Toast.LENGTH_SHORT).show()
                    scheduleLabelReset()
                }
            }
        }
    }

    override fun onDestroy() {
        resetLabelJob?.cancel()
        tileScope.cancel()
        super.onDestroy()
    }

    private fun scheduleLabelReset() {
        resetLabelJob = tileScope.launch(Dispatchers.Main) {
            delay(2_500L)
            val actualRunning = (application as SrunXApp).coordinator.runtimeState.value.serviceRunning
            updateTile(running = actualRunning)
        }
    }

    private fun updateTile(running: Boolean, transientLabel: String? = null) {
        val tile = qsTile ?: return
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(
            this,
            if (running) R.drawable.ic_srunx_stat else R.drawable.ic_srunx_tile_off,
        )
        tile.label = transientLabel ?: getString(R.string.tile_keep_alive_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (running) "后台监听中" else "未在运行"
        }
        tile.updateTile()
    }
}
