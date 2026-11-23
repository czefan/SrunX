package io.github.czefan.srunx.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import io.github.czefan.srunx.data.IpcCodec
import io.github.czefan.srunx.protocol.SrunConfig
import io.github.czefan.srunx.service.BgIpcProtocol
import io.github.czefan.srunx.service.SrunMonitorService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 主 UI 进程专用的轻量跨进程客户端，通过 Messenger 与 :bg 后台进程通信。
 * UI 进程不直接访问 DataStore 或网络状态回调，保持进程极简分离。
 */
class BgServiceClient(private val context: Context) {
    private val _uiState = MutableStateFlow(SrunUiState())
    val uiState: StateFlow<SrunUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val clientHandler = Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            BgIpcProtocol.MSG_STATE_SNAPSHOT -> {
                val config = IpcCodec.decodeConfig(msg.data.getString(BgIpcProtocol.KEY_CONFIG_JSON).orEmpty())
                val runtime = IpcCodec.decodeRuntime(msg.data.getString(BgIpcProtocol.KEY_RUNTIME_JSON).orEmpty())
                val logs = IpcCodec.decodeLogs(msg.data.getString(BgIpcProtocol.KEY_LOGS_JSON).orEmpty())
                _uiState.value = SrunUiState(
                    config = config,
                    runtime = runtime,
                    logs = logs,
                    configLoaded = true,
                )
                true
            }

            BgIpcProtocol.MSG_EVENT_MESSAGE -> {
                val text = msg.data.getString(BgIpcProtocol.KEY_MESSAGE).orEmpty()
                if (text.isNotEmpty()) {
                    _events.tryEmit(text)
                }
                true
            }

            else -> false
        }
    }
    private val clientMessenger = Messenger(clientHandler)

    private val lock = Any()
    private val pendingMessages = ArrayDeque<Message>()
    private var serviceMessenger: Messenger? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val remote = Messenger(service)
            val queued: List<Message>
            synchronized(lock) {
                serviceMessenger = remote
                queued = pendingMessages.toList()
                pendingMessages.clear()
            }
            sendDirect(remote, Message.obtain(null, BgIpcProtocol.MSG_REGISTER_CLIENT).apply {
                replyTo = clientMessenger
            })
            for (msg in queued) {
                sendDirect(remote, msg)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synchronized(lock) {
                serviceMessenger = null
            }
        }
    }

    fun bind() {
        synchronized(lock) {
            if (bound) return
            bound = true
        }
        val intent = Intent(context, SrunMonitorService::class.java)
        val ok = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!ok) {
            synchronized(lock) { bound = false }
        }
    }

    fun unbind() {
        val remote: Messenger?
        val wasBound: Boolean
        synchronized(lock) {
            wasBound = bound
            bound = false
            remote = serviceMessenger
            serviceMessenger = null
            pendingMessages.clear()
        }
        if (remote != null) {
            sendDirect(remote, Message.obtain(null, BgIpcProtocol.MSG_UNREGISTER_CLIENT).apply {
                replyTo = clientMessenger
            })
        }
        if (wasBound) {
            runCatching { context.unbindService(connection) }
        }
    }

    fun saveConfig(config: SrunConfig) {
        sendCommand(BgIpcProtocol.MSG_SAVE_CONFIG) {
            putString(BgIpcProtocol.KEY_CONFIG_JSON, IpcCodec.encodeConfig(config))
        }
    }

    fun loginNow() = sendCommand(BgIpcProtocol.MSG_LOGIN_NOW)

    fun logoutNow() = sendCommand(BgIpcProtocol.MSG_LOGOUT_NOW)

    fun refreshStatus() = sendCommand(BgIpcProtocol.MSG_REFRESH_STATUS)

    fun setAutoMonitor(enabled: Boolean) {
        sendCommand(BgIpcProtocol.MSG_SET_AUTO_MONITOR) {
            putBoolean(BgIpcProtocol.KEY_ENABLED, enabled)
        }
    }

    fun setAutoStartOnBoot(enabled: Boolean) {
        sendCommand(BgIpcProtocol.MSG_SET_AUTO_BOOT) {
            putBoolean(BgIpcProtocol.KEY_ENABLED, enabled)
        }
    }

    fun clearLogs() = sendCommand(BgIpcProtocol.MSG_CLEAR_LOGS)

    private fun sendCommand(what: Int, fillBundle: (Bundle.() -> Unit)? = null) {
        val msg = Message.obtain(null, what).apply {
            if (fillBundle != null) {
                data = Bundle().apply(fillBundle)
            }
        }
        val remote: Messenger?
        synchronized(lock) {
            remote = serviceMessenger
            if (remote == null) {
                pendingMessages.addLast(msg)
            }
        }
        if (remote != null) {
            sendDirect(remote, msg)
        } else {
            bind()
        }
    }

    private fun sendDirect(remote: Messenger, msg: Message) {
        runCatching { remote.send(msg) }.onFailure {
            synchronized(lock) {
                if (serviceMessenger === remote) {
                    serviceMessenger = null
                }
            }
        }
    }
}
