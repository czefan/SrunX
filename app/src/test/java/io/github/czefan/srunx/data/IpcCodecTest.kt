package io.github.czefan.srunx.data

import io.github.czefan.srunx.protocol.AuthLogEntry
import io.github.czefan.srunx.protocol.OnlineDevice
import io.github.czefan.srunx.protocol.SrunConfig
import io.github.czefan.srunx.protocol.SrunStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IpcCodecTest {

    @Test
    fun `config round-trip preserves special characters and toggles`() {
        val cfg = SrunConfig(
            host = "http://192.0.2.1",
            acId = "12",
            username = "20250001",
            password = "p@ss\"w\\rd!\n123",
            domainSuffix = "cmcc",
            autoMonitorEnabled = false,
            autoStartOnBoot = true,
        )
        val encoded = IpcCodec.encodeConfig(cfg)
        assertEquals(cfg, IpcCodec.decodeConfig(encoded))
    }

    @Test
    fun `runtime state round-trip preserves nested status and devices`() {
        val online = SrunRuntimeState(
            isBusy = true,
            serviceRunning = true,
            wifiConnected = true,
            lastStatus = SrunStatus(
                isOnline = true,
                username = "20250001@cmcc",
                productName = "学生",
                onlineIp = "192.0.2.10",
                clientIp = "192.0.2.10",
                userMac = "aa:bb:cc:dd:ee:ff",
                sessionBytes = 12345678L,
                totalBytes = 9876543210L,
                onlineDeviceTotal = 2,
                onlineDevices = listOf(
                    OnlineDevice("AndroidOS", "192.0.2.10", "1"),
                    OnlineDevice("Windows 10", "192.0.2.11", "2"),
                ),
                error = "ok",
                errorMsg = "",
                isSrunServer = true,
            ),
            lastMessage = "已在线",
            lastUpdatedMs = 1795421000000L,
        )
        assertEquals(online, IpcCodec.decodeRuntime(IpcCodec.encodeRuntime(online)))

        val idle = SrunRuntimeState(
            isBusy = false,
            serviceRunning = false,
            wifiConnected = false,
            lastStatus = null,
            lastMessage = "等待连接校园网",
            lastUpdatedMs = 0L,
        )
        val decodedIdle = IpcCodec.decodeRuntime(IpcCodec.encodeRuntime(idle))
        assertEquals(idle, decodedIdle)
        assertNull(decodedIdle.lastStatus)
    }

    @Test
    fun `logs round-trip preserves ordering and fields`() {
        val logs = listOf(
            AuthLogEntry(1795421000000L, "自动保活", true, "认证成功"),
            AuthLogEntry(1795420000000L, "快捷磁贴", false, "未连 Wi-Fi"),
        )
        assertEquals(logs, IpcCodec.decodeLogs(IpcCodec.encodeLogs(logs)))
    }
}
