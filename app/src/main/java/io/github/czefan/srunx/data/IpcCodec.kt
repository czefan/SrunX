package io.github.czefan.srunx.data

import io.github.czefan.srunx.protocol.AuthLogEntry
import io.github.czefan.srunx.protocol.OnlineDevice
import io.github.czefan.srunx.protocol.SrunConfig
import io.github.czefan.srunx.protocol.SrunStatus
import org.json.JSONArray
import org.json.JSONObject

/**
 * UI 进程与 :bg 后台进程间的纯 JSON 序列化工具，无反射且兼容 R8 压缩。
 */
object IpcCodec {
    fun encodeConfig(config: SrunConfig): String = JSONObject().apply {
        put("host", config.host)
        put("acId", config.acId)
        put("username", config.username)
        put("password", config.password)
        put("domainSuffix", config.domainSuffix)
        put("autoMonitorEnabled", config.autoMonitorEnabled)
        put("autoStartOnBoot", config.autoStartOnBoot)
    }.toString()

    fun decodeConfig(raw: String): SrunConfig {
        if (raw.isBlank()) return SrunConfig()
        return runCatching {
            val obj = JSONObject(raw)
            SrunConfig(
                host = obj.optString("host", ""),
                acId = obj.optString("acId", ""),
                username = obj.optString("username", ""),
                password = obj.optString("password", ""),
                domainSuffix = obj.optString("domainSuffix", ""),
                autoMonitorEnabled = obj.optBoolean("autoMonitorEnabled", true),
                autoStartOnBoot = obj.optBoolean("autoStartOnBoot", true),
            )
        }.getOrDefault(SrunConfig())
    }

    fun encodeRuntime(state: SrunRuntimeState): String = JSONObject().apply {
        put("isBusy", state.isBusy)
        put("serviceRunning", state.serviceRunning)
        put("wifiConnected", state.wifiConnected)
        put("lastMessage", state.lastMessage)
        put("lastUpdatedMs", state.lastUpdatedMs)
        state.lastStatus?.let { status ->
            put("lastStatus", encodeStatus(status))
        }
    }.toString()

    fun decodeRuntime(raw: String): SrunRuntimeState {
        if (raw.isBlank()) return SrunRuntimeState()
        return runCatching {
            val obj = JSONObject(raw)
            val statusObj = obj.optJSONObject("lastStatus")
            SrunRuntimeState(
                isBusy = obj.optBoolean("isBusy", false),
                serviceRunning = obj.optBoolean("serviceRunning", false),
                wifiConnected = obj.optBoolean("wifiConnected", false),
                lastStatus = statusObj?.let(::decodeStatus),
                lastMessage = obj.optString("lastMessage", "等待连接校园网"),
                lastUpdatedMs = obj.optLong("lastUpdatedMs", 0L),
            )
        }.getOrDefault(SrunRuntimeState())
    }

    fun encodeLogs(logs: List<AuthLogEntry>): String {
        val arr = JSONArray()
        for (entry in logs) {
            arr.put(
                JSONObject().apply {
                    put("ts", entry.timestampMs)
                    put("trigger", entry.trigger)
                    put("ok", entry.success)
                    put("msg", entry.message)
                },
            )
        }
        return arr.toString()
    }

    fun decodeLogs(raw: String): List<AuthLogEntry> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(
                        AuthLogEntry(
                            timestampMs = obj.optLong("ts", 0L),
                            trigger = obj.optString("trigger", ""),
                            success = obj.optBoolean("ok", false),
                            message = obj.optString("msg", ""),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeStatus(status: SrunStatus): JSONObject = JSONObject().apply {
        put("isOnline", status.isOnline)
        put("username", status.username)
        put("productName", status.productName)
        put("onlineIp", status.onlineIp)
        put("clientIp", status.clientIp)
        put("userMac", status.userMac)
        put("sessionBytes", status.sessionBytes)
        put("totalBytes", status.totalBytes)
        put("onlineDeviceTotal", status.onlineDeviceTotal)
        put("error", status.error)
        put("errorMsg", status.errorMsg)
        put("isSrunServer", status.isSrunServer)
        val devicesArr = JSONArray()
        for (dev in status.onlineDevices) {
            devicesArr.put(
                JSONObject().apply {
                    put("osName", dev.osName)
                    put("ip", dev.ip)
                    put("radOnlineId", dev.radOnlineId)
                },
            )
        }
        put("onlineDevices", devicesArr)
    }

    private fun decodeStatus(obj: JSONObject): SrunStatus {
        val devicesArr = obj.optJSONArray("onlineDevices")
        val devices = if (devicesArr != null) {
            buildList(devicesArr.length()) {
                for (i in 0 until devicesArr.length()) {
                    val item = devicesArr.optJSONObject(i) ?: continue
                    add(
                        OnlineDevice(
                            osName = item.optString("osName", ""),
                            ip = item.optString("ip", ""),
                            radOnlineId = item.optString("radOnlineId", ""),
                        ),
                    )
                }
            }
        } else {
            emptyList()
        }

        return SrunStatus(
            isOnline = obj.optBoolean("isOnline", false),
            username = obj.optString("username", ""),
            productName = obj.optString("productName", ""),
            onlineIp = obj.optString("onlineIp", ""),
            clientIp = obj.optString("clientIp", ""),
            userMac = obj.optString("userMac", ""),
            sessionBytes = obj.optLong("sessionBytes", 0L),
            totalBytes = obj.optLong("totalBytes", 0L),
            onlineDeviceTotal = obj.optInt("onlineDeviceTotal", devices.size),
            onlineDevices = devices,
            error = obj.optString("error", ""),
            errorMsg = obj.optString("errorMsg", ""),
            isSrunServer = obj.optBoolean("isSrunServer", false),
        )
    }
}
