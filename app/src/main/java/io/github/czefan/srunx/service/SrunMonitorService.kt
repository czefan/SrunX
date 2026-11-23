package io.github.czefan.srunx.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import io.github.czefan.srunx.R
import io.github.czefan.srunx.SrunXApp
import io.github.czefan.srunx.data.IpcCodec
import io.github.czefan.srunx.data.SrunRuntimeState
import io.github.czefan.srunx.protocol.AuthLogEntry
import io.github.czefan.srunx.protocol.SrunConfig
import io.github.czefan.srunx.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

class SrunMonitorService : Service() {
    private data class Snapshot(
        val config: SrunConfig,
        val runtime: SrunRuntimeState,
        val logs: List<AuthLogEntry>,
    )

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val clients = CopyOnWriteArraySet<Messenger>()

    @Volatile
    private var latestSnapshot: Snapshot? = null
    private var observingState = false
    private var lastConfigWriteJob: Job? = null
    private var lastTileServiceRunning: Boolean? = null
    private var lastTileOnline: Boolean? = null

    private val incomingHandler = Handler(Looper.getMainLooper()) { msg ->
        handleIncomingMessage(msg)
        true
    }
    private val incomingMessenger = Messenger(incomingHandler)

    override fun onBind(intent: Intent?): IBinder {
        ensureObservingState()
        return incomingMessenger.binder
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as SrunXApp
        val action = intent?.action

        if (action == ACTION_STOP) {
            stopForegroundMonitoring(app)
            return START_NOT_STICKY
        }

        val initialText = app.coordinator.runtimeState.value.lastMessage
        startForegroundWithType(buildNotification(initialText))
        app.coordinator.setServiceRunning(true)
        ensureObservingState()

        if (action == ACTION_LOGIN_NOW) {
            serviceScope.launch(Dispatchers.IO) {
                app.coordinator.manualLogin(trigger = "通知栏")
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        observingState = false
        clients.clear()
        serviceScope.cancel()
        (application as? SrunXApp)?.coordinator?.setServiceRunning(false)
        requestTileSync(SrunKeepAliveTileService::class.java)
        super.onDestroy()
    }

    private fun ensureObservingState() {
        if (observingState) return
        observingState = true
        val app = application as SrunXApp
        serviceScope.launch {
            combine(
                app.repository.configFlow,
                app.coordinator.runtimeState,
                app.repository.logsFlow,
            ) { config, runtime, logs ->
                Snapshot(config, runtime, logs)
            }.collect { snapshot ->
                latestSnapshot = snapshot
                if (snapshot.runtime.serviceRunning) {
                    val manager = getSystemService(NotificationManager::class.java)
                    val timeHint = if (snapshot.runtime.lastUpdatedMs > 0L) {
                        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                        "${fmt.format(Date(snapshot.runtime.lastUpdatedMs))} · "
                    } else {
                        ""
                    }
                    manager.notify(
                        NOTIFICATION_ID,
                        buildNotification("$timeHint${snapshot.runtime.lastMessage}"),
                    )
                }
                if (lastTileServiceRunning != snapshot.runtime.serviceRunning) {
                    lastTileServiceRunning = snapshot.runtime.serviceRunning
                    requestTileSync(SrunKeepAliveTileService::class.java)
                }
                val isOnline = snapshot.runtime.lastStatus?.isOnline == true
                if (lastTileOnline != isOnline) {
                    lastTileOnline = isOnline
                    requestTileSync(SrunTileService::class.java)
                }
                broadcastSnapshot(snapshot)
            }
        }
    }

    private fun handleIncomingMessage(msg: Message) {
        val app = application as SrunXApp
        when (msg.what) {
            BgIpcProtocol.MSG_REGISTER_CLIENT -> {
                val replyTo = msg.replyTo ?: return
                clients.add(replyTo)
                serviceScope.launch(Dispatchers.IO) {
                    val snap = latestSnapshot ?: Snapshot(
                        config = app.repository.configFlow.first(),
                        runtime = app.coordinator.runtimeState.value,
                        logs = app.repository.logsFlow.first(),
                    )
                    sendSnapshotTo(replyTo, snap)
                    if (snap.config.autoMonitorEnabled && !app.coordinator.runtimeState.value.serviceRunning) {
                        runCatching { start(applicationContext) }
                    }
                }
            }

            BgIpcProtocol.MSG_UNREGISTER_CLIENT -> {
                msg.replyTo?.let { clients.remove(it) }
            }

            BgIpcProtocol.MSG_SAVE_CONFIG -> {
                val cfgJson = msg.data.getString(BgIpcProtocol.KEY_CONFIG_JSON).orEmpty()
                val prev = lastConfigWriteJob
                lastConfigWriteJob = serviceScope.launch(Dispatchers.IO) {
                    prev?.join()
                    val cfg = IpcCodec.decodeConfig(cfgJson)
                    app.repository.saveConfig(cfg)
                    broadcastEvent("配置已加密保存")
                }
            }

            BgIpcProtocol.MSG_LOGIN_NOW -> {
                val pendingWrite = lastConfigWriteJob
                serviceScope.launch(Dispatchers.IO) {
                    pendingWrite?.join()
                    val res = app.coordinator.manualLogin(trigger = "手动")
                    broadcastEvent(if (res.success) "登录成功" else res.message)
                }
            }

            BgIpcProtocol.MSG_LOGOUT_NOW -> {
                val pendingWrite = lastConfigWriteJob
                serviceScope.launch(Dispatchers.IO) {
                    pendingWrite?.join()
                    val res = app.coordinator.manualLogout(trigger = "手动")
                    broadcastEvent(if (res.success) "已注销下线" else res.message)
                }
            }

            BgIpcProtocol.MSG_REFRESH_STATUS -> {
                val pendingWrite = lastConfigWriteJob
                serviceScope.launch(Dispatchers.IO) {
                    pendingWrite?.join()
                    app.coordinator.refreshStatus().onFailure { err ->
                        broadcastEvent(err.message ?: "刷新失败")
                    }
                }
            }

            BgIpcProtocol.MSG_SET_AUTO_MONITOR -> {
                val enabled = msg.data.getBoolean(BgIpcProtocol.KEY_ENABLED, true)
                val prev = lastConfigWriteJob
                lastConfigWriteJob = serviceScope.launch(Dispatchers.IO) {
                    prev?.join()
                    app.repository.setAutoMonitor(enabled)
                    if (enabled) {
                        runCatching { start(applicationContext) }
                            .onFailure { broadcastEvent("启动后台监听失败: ${it.message}") }
                    } else {
                        stopForegroundMonitoring(app)
                    }
                }
            }

            BgIpcProtocol.MSG_SET_AUTO_BOOT -> {
                val enabled = msg.data.getBoolean(BgIpcProtocol.KEY_ENABLED, true)
                val prev = lastConfigWriteJob
                lastConfigWriteJob = serviceScope.launch(Dispatchers.IO) {
                    prev?.join()
                    app.repository.setAutoStartOnBoot(enabled)
                }
            }

            BgIpcProtocol.MSG_CLEAR_LOGS -> {
                serviceScope.launch(Dispatchers.IO) {
                    app.repository.clearLogs()
                }
            }
        }
    }

    private fun stopForegroundMonitoring(app: SrunXApp) {
        app.coordinator.setServiceRunning(false)
        requestTileSync(SrunKeepAliveTileService::class.java)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun requestTileSync(serviceClass: Class<out TileService>) {
        runCatching {
            TileService.requestListeningState(
                this,
                ComponentName(this, serviceClass),
            )
        }
    }

    private fun broadcastSnapshot(snapshot: Snapshot) {
        if (clients.isEmpty()) return
        val bundle = Bundle().apply {
            putString(BgIpcProtocol.KEY_CONFIG_JSON, IpcCodec.encodeConfig(snapshot.config))
            putString(BgIpcProtocol.KEY_RUNTIME_JSON, IpcCodec.encodeRuntime(snapshot.runtime))
            putString(BgIpcProtocol.KEY_LOGS_JSON, IpcCodec.encodeLogs(snapshot.logs))
        }
        for (client in clients) {
            val out = Message.obtain(null, BgIpcProtocol.MSG_STATE_SNAPSHOT).apply {
                data = Bundle(bundle)
            }
            if (runCatching { client.send(out) }.isFailure) {
                clients.remove(client)
            }
        }
    }

    private fun sendSnapshotTo(client: Messenger, snapshot: Snapshot) {
        val out = Message.obtain(null, BgIpcProtocol.MSG_STATE_SNAPSHOT).apply {
            data = Bundle().apply {
                putString(BgIpcProtocol.KEY_CONFIG_JSON, IpcCodec.encodeConfig(snapshot.config))
                putString(BgIpcProtocol.KEY_RUNTIME_JSON, IpcCodec.encodeRuntime(snapshot.runtime))
                putString(BgIpcProtocol.KEY_LOGS_JSON, IpcCodec.encodeLogs(snapshot.logs))
            }
        }
        if (runCatching { client.send(out) }.isFailure) {
            clients.remove(client)
        }
    }

    private fun broadcastEvent(message: String) {
        for (client in clients) {
            val out = Message.obtain(null, BgIpcProtocol.MSG_EVENT_MESSAGE).apply {
                data = Bundle().apply { putString(BgIpcProtocol.KEY_MESSAGE, message) }
            }
            if (runCatching { client.send(out) }.isFailure) {
                clients.remove(client)
            }
        }
    }

    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "校园网后台保活监听",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "用于保持校园网 Wi-Fi 状态监听，可静默折叠隐藏"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val loginNowIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SrunMonitorService::class.java).apply { action = ACTION_LOGIN_NOW },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val action = Notification.Action.Builder(
            null,
            "立即登录",
            loginNowIntent,
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_srunx_stat)
            .setContentTitle("SrunX 监听中")
            .setContentText(statusText)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(action)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "srunx_monitor"
        const val NOTIFICATION_ID = 1001
        const val ACTION_LOGIN_NOW = "io.github.czefan.srunx.action.LOGIN_NOW"
        const val ACTION_STOP = "io.github.czefan.srunx.action.STOP_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, SrunMonitorService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SrunMonitorService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
        }
    }
}
