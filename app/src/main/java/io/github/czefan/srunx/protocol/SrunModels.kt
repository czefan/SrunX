package io.github.czefan.srunx.protocol

import java.util.Locale

data class SrunConfig(
    val host: String = "",
    val acId: String = "",
    val username: String = "",
    val password: String = "",
    val domainSuffix: String = "",
    val autoMonitorEnabled: Boolean = true,
    val autoStartOnBoot: Boolean = true,
) {
    fun normalizedHost(): String {
        val trimmed = host.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }

    fun effectiveUsername(): String {
        val user = username.trim()
        val domain = domainSuffix.trim().removePrefix("@")
        if (user.isEmpty() || domain.isEmpty() || user.contains("@")) {
            return user
        }
        return "$user@$domain"
    }

    fun isReadyForLogin(): Boolean =
        normalizedHost().isNotEmpty() && username.trim().isNotEmpty() && password.isNotEmpty()
}

data class OnlineDevice(
    val osName: String,
    val ip: String,
    val radOnlineId: String,
)

data class SrunStatus(
    val isOnline: Boolean,
    val username: String = "",
    val productName: String = "",
    val onlineIp: String = "",
    val clientIp: String = "",
    val userMac: String = "",
    val sessionBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val onlineDeviceTotal: Int = 0,
    val onlineDevices: List<OnlineDevice> = emptyList(),
    val error: String = "",
    val errorMsg: String = "",
    val isSrunServer: Boolean = false,
)

data class SrunActionResult(
    val success: Boolean,
    val message: String,
    val status: SrunStatus? = null,
)

data class AuthLogEntry(
    val timestampMs: Long,
    val trigger: String,
    val success: Boolean,
    val message: String,
)

fun formatTrafficBytes(bytes: Long): String {
    var value = bytes.toDouble()
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    var idx = 0
    while (kotlin.math.abs(value) >= 1000.0 && idx < units.lastIndex) {
        value /= 1000.0
        idx++
    }
    return if (idx == 0) {
        "${bytes} B"
    } else {
        String.format(Locale.US, "%.2f %s", value, units[idx])
    }
}
