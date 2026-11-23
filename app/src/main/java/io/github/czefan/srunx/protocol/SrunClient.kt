package io.github.czefan.srunx.protocol

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * 深澜（SRun3000）HTTP 协议客户端。
 * 通过注入 connectionFactory 支持在 Android 上使用 wifiNetwork.openConnection() 强制走 Wi-Fi 网卡。
 */
class SrunClient(
    private val connectionFactory: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
    private val connectTimeoutMs: Int = 2500,
    private val readTimeoutMs: Int = 4000,
) {
    private data class HttpResponse(
        val code: Int,
        val body: String,
        val headers: Map<String?, List<String>>,
    ) {
        fun headerIgnoreCase(name: String): String? =
            headers.entries.firstOrNull { it.key?.equals(name, ignoreCase = true) == true }
                ?.value?.firstOrNull()
    }

    fun getStatus(rawHost: String): SrunStatus {
        val host = normalizeHostOrThrow(rawHost)
        val ts = System.currentTimeMillis()
        val cb = "jQuery_$ts"
        val resp = httpGetChecked(
            "$host/cgi-bin/rad_user_info",
            mapOf("callback" to cb, "_" to ts.toString()),
            "查询网关状态失败",
        )

        val json = parseJsonp(resp.body)
        val error = json.optString("error", "")
        val clientIp = json.optString("client_ip", "")
        val onlineIp = json.optString("online_ip", "")
        val username = json.optString("user_name", "")
        val hasSrunHeader = !resp.headerIgnoreCase("Srun-Server").isNullOrBlank() ||
            !resp.headerIgnoreCase("Srunflag").isNullOrBlank()
        val hasVersion = !(json.opt("srun_ver") as? String).isNullOrBlank()
        val hasOfflineStatus = error == "not_online_error" &&
            (clientIp.isNotBlank() || onlineIp.isNotBlank())
        val hasOnlineStatus = error == "ok" && username.isNotBlank() && onlineIp.isNotBlank() &&
            (json.has("products_name") || json.has("online_device_total"))
        val isSrun = hasSrunHeader || hasVersion || hasOfflineStatus || hasOnlineStatus
        val isOnline = isSrun && error == "ok"

        val devices = if (isOnline) {
            parseOnlineDevices(json.opt("online_device_detail"))
        } else {
            emptyList()
        }

        return SrunStatus(
            isOnline = isOnline,
            username = username,
            productName = json.optString("products_name", ""),
            onlineIp = onlineIp,
            clientIp = clientIp,
            userMac = json.optString("user_mac", ""),
            sessionBytes = json.optLong("all_bytes", 0L),
            totalBytes = json.optLong("sum_bytes", 0L),
            onlineDeviceTotal = json.optString("online_device_total", "").toIntOrNull()
                ?: json.optInt("online_device_total", devices.size),
            onlineDevices = devices,
            error = error,
            errorMsg = json.optString("error_msg", ""),
            isSrunServer = isSrun,
        )
    }

    fun detectAcId(rawHost: String): String {
        val host = normalizeHostOrThrow(rawHost)
        var currentUrl = "$host/"
        repeat(4) {
            val resp = httpGetRaw(currentUrl, followRedirects = false)
            val location = resp.headerIgnoreCase("Location")
            if (!location.isNullOrBlank()) {
                extractAcId(location)?.let { return it }
                if (resp.code in 300..399) {
                    currentUrl = URL(URL(currentUrl), location).toString()
                    return@repeat
                }
            }
            extractAcId(resp.body)?.let { return it }
            return "1"
        }
        return "1"
    }

    fun detectIp(rawHost: String, acId: String): String {
        val host = normalizeHostOrThrow(rawHost)
        val paths = listOf("/srun_portal_pc", "/srun_portal_phone")
        for (path in paths) {
            val html = runCatching {
                httpGet("$host$path", mapOf("ac_id" to acId)).body
            }.getOrNull() ?: continue
            extractIpFromHtml(html)?.let { return it }
        }
        throw IOException("未能从认证页面解析到终端 IP")
    }

    fun login(config: SrunConfig): SrunActionResult {
        val host = normalizeHostOrThrow(config.normalizedHost())
        val username = config.effectiveUsername()
        if (username.isEmpty() || config.password.isEmpty()) {
            return SrunActionResult(false, "请先填写完整的学号与密码")
        }

        val acId = config.acId.trim().ifEmpty { detectAcId(host) }
        val ts = System.currentTimeMillis()
        val cb = "jQuery_$ts"

        var chalJson = parseJsonp(
            httpGetChecked(
                "$host/cgi-bin/get_challenge",
                mapOf(
                    "callback" to cb,
                    "username" to username,
                    "ip" to "",
                    "_" to ts.toString(),
                ),
                "获取 challenge 失败",
            ).body,
        )
        var challenge = chalJson.optString("challenge", "")
        if (challenge.isEmpty()) {
            val err = chalJson.optString("error_msg").ifBlank { chalJson.optString("error", "获取 challenge 失败") }
            return SrunActionResult(false, err)
        }

        var ip = chalJson.optString("client_ip", "").ifBlank { chalJson.optString("online_ip", "") }
        if (ip.isBlank()) {
            ip = detectIp(host, acId)
            val ts2 = System.currentTimeMillis()
            val cb2 = "jQuery_$ts2"
            chalJson = parseJsonp(
                httpGetChecked(
                    "$host/cgi-bin/get_challenge",
                    mapOf(
                        "callback" to cb2,
                        "username" to username,
                        "ip" to ip,
                        "_" to ts2.toString(),
                    ),
                    "获取 challenge 失败",
                ).body,
            )
            challenge = chalJson.optString("challenge", "")
            if (challenge.isEmpty()) {
                val err = chalJson.optString("error_msg").ifBlank { chalJson.optString("error", "获取 challenge 失败") }
                return SrunActionResult(false, err)
            }
        }

        val hmd5 = SrunCrypto.hmacMd5Hex(challenge, config.password)
        val info = SrunCrypto.encodeInfo(
            username = username,
            password = config.password,
            ip = ip,
            acId = acId,
            challenge = challenge,
        )
        val chksum = SrunCrypto.buildChecksum(
            challenge = challenge,
            username = username,
            hmd5 = hmd5,
            acId = acId,
            ip = ip,
            info = info,
        )

        val portalJson = parseJsonp(
            httpGetChecked(
                "$host/cgi-bin/srun_portal",
                mapOf(
                    "callback" to cb,
                    "action" to "login",
                    "username" to username,
                    "password" to "{MD5}$hmd5",
                    "os" to "AndroidOS",
                    "name" to "Smartphones/PDAs/Tablets",
                    "double_stack" to "0",
                    "chksum" to chksum,
                    "info" to info,
                    "ac_id" to acId,
                    "ip" to ip,
                    "n" to SrunCrypto.N_CONST,
                    "type" to SrunCrypto.TYPE_CONST,
                    "_" to (ts + 1).toString(),
                ),
                "认证请求失败",
            ).body,
        )

        val ok = portalJson.optString("error") == "ok" || portalJson.optString("suc_msg") == "login_ok"
        val msg = portalJson.optString("ploy_msg")
            .ifBlank { portalJson.optString("suc_msg") }
            .ifBlank { portalJson.optString("error_msg") }
            .ifBlank { portalJson.optString("error") }
            .ifBlank { if (ok) "登录成功" else "登录失败" }

        val updatedStatus = if (ok) {
            runCatching { getStatus(host) }.getOrNull()?.takeIf { it.isSrunServer }
        } else {
            null
        }
        return SrunActionResult(ok, msg, updatedStatus)
    }

    fun logout(config: SrunConfig): SrunActionResult {
        val host = normalizeHostOrThrow(config.normalizedHost())
        val username = config.effectiveUsername()
        if (username.isEmpty()) {
            return SrunActionResult(false, "请先填写学号")
        }
        val acId = config.acId.trim().ifEmpty { detectAcId(host) }
        val currentStatus = runCatching { getStatus(host) }.getOrNull()?.takeIf { it.isSrunServer }
        val ip = currentStatus?.onlineIp?.ifBlank { currentStatus.clientIp }
            ?.ifBlank { null }
            ?: detectIp(host, acId)

        val ts = System.currentTimeMillis()
        val cb = "jQuery_$ts"
        val response = httpGetChecked(
            "$host/cgi-bin/srun_portal",
            mapOf(
                "callback" to cb,
                "action" to "logout",
                "username" to username,
                "ip" to ip,
                "ac_id" to acId,
                "_" to ts.toString(),
            ),
            "注销请求失败",
        )
        val json = parseJsonp(response.body)

        val ok = json.optString("error") == "ok" || json.optString("suc_msg") == "logout_ok"
        val msg = json.optString("suc_msg")
            .ifBlank { json.optString("error_msg") }
            .ifBlank { json.optString("error") }
            .ifBlank { if (ok) "注销成功" else "注销失败" }

        val updatedStatus = runCatching { getStatus(host) }.getOrNull()?.takeIf { it.isSrunServer }
        return SrunActionResult(ok, msg, updatedStatus)
    }

    private fun httpGetChecked(
        url: String,
        params: Map<String, String>,
        errorPrefix: String,
    ): HttpResponse {
        val resp = httpGet(url, params, followRedirects = false)
        if (resp.code !in 200..299) throw IOException("$errorPrefix（HTTP ${resp.code}）")
        return resp
    }

    private fun httpGet(
        url: String,
        params: Map<String, String>,
        followRedirects: Boolean = true,
    ): HttpResponse {
        val query = params.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        val fullUrl = if (query.isEmpty()) url else "$url?$query"
        return httpGetRaw(fullUrl, followRedirects = followRedirects)
    }

    private fun httpGetRaw(fullUrl: String, followRedirects: Boolean): HttpResponse {
        val conn = connectionFactory(URL(fullUrl))
        conn.requestMethod = "GET"
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = followRedirects
        conn.useCaches = false
        conn.setRequestProperty("User-Agent", "Mozilla/5.0")
        conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        conn.setRequestProperty("Connection", "close")
        try {
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream ?: conn.inputStream else conn.inputStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return HttpResponse(code, body, conn.headerFields.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private val JSONP_REGEX = Regex("""^\s*\w+\((.*)\)\s*;?\s*$""", RegexOption.DOT_MATCHES_ALL)
        private val AC_ID_QUERY_REGEX = Regex("""[?&]ac_id=(\d+)""")
        private val AC_ID_JS_REGEX = Regex("""\bacid\s*[:=]\s*["']?(\d+)["']?""", RegexOption.IGNORE_CASE)
        private val HTML_IP_REGEX = Regex("""\b(?:ip|client_ip)\s*:\s*["']([^"']+)["']""")

        fun parseJsonp(text: String): JSONObject {
            val trimmed = text.trim()
            val match = JSONP_REGEX.matchEntire(trimmed)
            val jsonBody = match?.groupValues?.get(1) ?: trimmed
            return JSONObject(jsonBody)
        }

        fun extractAcId(input: String): String? =
            AC_ID_QUERY_REGEX.find(input)?.groupValues?.get(1)
                ?: AC_ID_JS_REGEX.find(input)?.groupValues?.get(1)

        fun extractIpFromHtml(html: String): String? =
            HTML_IP_REGEX.find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

        private fun parseOnlineDevices(raw: Any?): List<OnlineDevice> {
            val obj = when (raw) {
                is JSONObject -> raw
                is String -> raw.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
                else -> null
            } ?: return emptyList()

            val list = mutableListOf<OnlineDevice>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val dev = obj.optJSONObject(key) ?: continue
                list.add(
                    OnlineDevice(
                        osName = dev.optString("os_name", dev.optString("class_name", "未知设备")),
                        ip = dev.optString("ip", ""),
                        radOnlineId = dev.optString("rad_online_id", key),
                    ),
                )
            }
            return list
        }

        private fun normalizeHostOrThrow(host: String): String {
            val normalized = SrunConfig(host = host).normalizedHost()
            if (normalized.isEmpty()) {
                throw IllegalArgumentException("请先配置校园网认证网关地址")
            }
            val lower = normalized.lowercase(Locale.US)
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                throw IllegalArgumentException("网关地址必须以 http:// 或 https:// 开头")
            }
            return normalized
        }
    }
}
