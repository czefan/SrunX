package io.github.czefan.srunx.protocol

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder

class SrunClientTest {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    @Volatile
    private var onlineState = false

    @Volatile
    private var lastLoginParams: Map<String, String> = emptyMap()

    @Volatile
    private var lastLogoutParams: Map<String, String> = emptyMap()

    @Volatile
    private var statusBodyOverride: String? = null

    @Volatile
    private var statusHttpCode = 200

    @Volatile
    private var includeSrunHeader = true

    @Volatile
    private var srunHeaderName = "Srun-Server"

    @Volatile
    private var challengeHttpCode = 200

    @Volatile
    private var portalHttpCode = 200

    private val testChallenge = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val testClientIp = "192.0.2.10"

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        baseUrl = "http://127.0.0.1:${server.address.port}"

        server.createContext("/") { ex ->
            when (ex.requestURI.path) {
                "/" -> {
                    ex.responseHeaders.add("Location", "$baseUrl/srun_portal_pc?ac_id=7&theme=basic")
                    sendText(ex, 302, "")
                }
                else -> sendText(ex, 404, "not found")
            }
        }

        server.createContext("/cgi-bin/rad_user_info") { ex ->
            val cb = parseQuery(ex.requestURI.rawQuery)["callback"] ?: "cb"
            if (includeSrunHeader) {
                val value = if (srunHeaderName.equals("Srunflag", ignoreCase = true)) {
                    "SRun portal server golang version"
                } else {
                    "SRunCGIAuthIntfSvr V1.18"
                }
                ex.responseHeaders.add(srunHeaderName, value)
            }
            if (statusHttpCode == 302) {
                ex.responseHeaders.add("Location", "$baseUrl/cgi-bin/get_challenge")
            }
            val body = if (onlineState) {
                """$cb({"error":"ok","user_name":"20250001@cmcc","products_name":"学生","online_ip":"$testClientIp","user_mac":"aa:bb:cc:dd:ee:ff","all_bytes":12345678,"sum_bytes":9876543210,"online_device_total":"2","online_device_detail":"{\"1\":{\"os_name\":\"AndroidOS\",\"ip\":\"$testClientIp\",\"rad_online_id\":\"1\"},\"2\":{\"os_name\":\"Windows 10\",\"ip\":\"192.0.2.11\",\"rad_online_id\":\"2\"}}"})"""
            } else {
                """$cb({"error":"not_online_error","error_msg":"","client_ip":"$testClientIp","srun_ver":"SRunCGIAuthIntfSvr V1.18"})"""
            }
            sendText(ex, statusHttpCode, statusBodyOverride ?: body)
        }

        server.createContext("/cgi-bin/get_challenge") { ex ->
            val cb = parseQuery(ex.requestURI.rawQuery)["callback"] ?: "cb"
            ex.responseHeaders.add("Srun-Server", "SRunCGIAuthIntfSvr V1.18")
            if (challengeHttpCode == 302) {
                ex.responseHeaders.add("Location", "$baseUrl/cgi-bin/rad_user_info")
            }
            val body = """$cb({"challenge":"$testChallenge","client_ip":"$testClientIp","online_ip":"$testClientIp","error":"ok","res":"ok"})"""
            sendText(ex, challengeHttpCode, body)
        }

        server.createContext("/cgi-bin/srun_portal") { ex ->
            val params = parseQuery(ex.requestURI.rawQuery)
            val cb = params["callback"] ?: "cb"
            ex.responseHeaders.add("Srun-Server", "SRunCGIAuthIntfSvr V1.18")
            if (portalHttpCode == 302) {
                ex.responseHeaders.add("Location", "$baseUrl/cgi-bin/rad_user_info")
            }
            if (portalHttpCode !in 200..299) {
                sendText(ex, portalHttpCode, """$cb({"error":"http_error"})""")
                return@createContext
            }
            when (params["action"]) {
                "login" -> {
                    lastLoginParams = params
                    onlineState = true
                    sendText(ex, 200, """$cb({"error":"ok","suc_msg":"login_ok","ploy_msg":"E0000: Login is successful."})""")
                }
                "logout" -> {
                    lastLogoutParams = params
                    onlineState = false
                    sendText(ex, 200, """$cb({"error":"ok","suc_msg":"logout_ok"})""")
                }
                else -> sendText(ex, 400, """$cb({"error":"bad_action"})""")
            }
        }

        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `login detects ac_id computes exact checksum and parses online status`() {
        val client = SrunClient()
        val cfg = SrunConfig(
            host = baseUrl,
            acId = "",
            username = "20250001",
            password = "secret_password",
            domainSuffix = "cmcc",
        )

        val offlineStatus = client.getStatus(baseUrl)
        assertTrue(offlineStatus.isSrunServer)
        assertFalse(offlineStatus.isOnline)
        assertEquals(testClientIp, offlineStatus.clientIp)

        val loginRes = client.login(cfg)
        assertTrue(loginRes.success)
        assertEquals("E0000: Login is successful.", loginRes.message)
        assertEquals("7", lastLoginParams["ac_id"])
        assertEquals("20250001@cmcc", lastLoginParams["username"])
        assertEquals("AndroidOS", lastLoginParams["os"])

        val expectedHmd5 = SrunCrypto.hmacMd5Hex(testChallenge, "secret_password")
        val expectedInfo = SrunCrypto.encodeInfo("20250001@cmcc", "secret_password", testClientIp, "7", testChallenge)
        val expectedChk = SrunCrypto.buildChecksum(testChallenge, "20250001@cmcc", expectedHmd5, "7", testClientIp, expectedInfo)
        assertEquals("{MD5}$expectedHmd5", lastLoginParams["password"])
        assertEquals(expectedInfo, lastLoginParams["info"])
        assertEquals(expectedChk, lastLoginParams["chksum"])

        val status = loginRes.status!!
        assertTrue(status.isOnline)
        assertEquals(testClientIp, status.onlineIp)
        assertEquals(2, status.onlineDevices.size)
        assertEquals("AndroidOS", status.onlineDevices[0].osName)

        val logoutRes = client.logout(cfg)
        assertTrue(logoutRes.success)
        assertEquals("7", lastLogoutParams["ac_id"])
        assertEquals(testClientIp, lastLogoutParams["ip"])
        assertFalse(logoutRes.status!!.isOnline)
    }

    @Test
    fun `getStatus rejects generic JSON errors and online responses`() {
        includeSrunHeader = false
        val client = SrunClient()
        listOf(
            """{"error":"not_found"}""",
            """{"error":"ok","client_ip":"$testClientIp","online_ip":"$testClientIp"}""",
        ).forEach { body ->
            statusBodyOverride = body
            val status = client.getStatus(baseUrl)
            assertFalse(status.isSrunServer)
            assertFalse(status.isOnline)
        }
    }

    @Test
    fun `getStatus accepts known offline responses without the Srun header`() {
        includeSrunHeader = false
        val client = SrunClient()
        assertTrue(client.getStatus(baseUrl).isSrunServer)

        statusBodyOverride = """{"error":"not_online_error","client_ip":"$testClientIp"}"""
        val status = client.getStatus(baseUrl)
        assertTrue(status.isSrunServer)
        assertFalse(status.isOnline)
    }

    @Test
    fun `getStatus accepts a headerless online response with Srun account fields`() {
        includeSrunHeader = false
        onlineState = true
        val status = SrunClient().getStatus(baseUrl)
        assertTrue(status.isSrunServer)
        assertTrue(status.isOnline)
    }

    @Test
    fun `getStatus rejects HTTP errors even with Srun markers`() {
        statusHttpCode = 404
        val error = assertThrows(IOException::class.java) { SrunClient().getStatus(baseUrl) }
        assertTrue(error.message.orEmpty().contains("HTTP 404"))
    }

    @Test
    fun `getStatus does not use a redirect destination to identify the gateway`() {
        statusHttpCode = 302
        val error = assertThrows(IOException::class.java) { SrunClient().getStatus(baseUrl) }
        assertTrue(error.message.orEmpty().contains("HTTP 302"))
    }

    @Test
    fun `getStatus accepts Srunflag header and online_ip offline responses`() {
        val client = SrunClient()
        srunHeaderName = "Srunflag"
        statusBodyOverride = """{"error":"not_online_error"}"""
        val headerStatus = client.getStatus(baseUrl)
        assertTrue(headerStatus.isSrunServer)
        assertFalse(headerStatus.isOnline)

        includeSrunHeader = false
        statusBodyOverride = """{"error":"not_online_error","online_ip":"$testClientIp"}"""
        val onlineIpStatus = client.getStatus(baseUrl)
        assertTrue(onlineIpStatus.isSrunServer)
        assertFalse(onlineIpStatus.isOnline)
    }

    @Test
    fun `login rejects HTTP errors and redirects on challenge and portal endpoints`() {
        val client = SrunClient()
        val cfg = SrunConfig(
            host = baseUrl,
            acId = "7",
            username = "20250001",
            password = "secret_password",
        )

        challengeHttpCode = 404
        val chal404 = assertThrows(IOException::class.java) { client.login(cfg) }
        assertTrue(chal404.message.orEmpty().contains("HTTP 404"))

        challengeHttpCode = 302
        val chal302 = assertThrows(IOException::class.java) { client.login(cfg) }
        assertTrue(chal302.message.orEmpty().contains("HTTP 302"))

        challengeHttpCode = 200
        portalHttpCode = 404
        val portal404 = assertThrows(IOException::class.java) { client.login(cfg) }
        assertTrue(portal404.message.orEmpty().contains("HTTP 404"))

        portalHttpCode = 302
        val portal302 = assertThrows(IOException::class.java) { client.login(cfg) }
        assertTrue(portal302.message.orEmpty().contains("HTTP 302"))
    }

    @Test
    fun `cancelled login never submits the portal request`() {
        val cfg = SrunConfig(host = baseUrl, acId = "7", username = "student", password = "test")
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            SrunClient().login(cfg) { throw java.util.concurrent.CancellationException() }
        }
        assertTrue(lastLoginParams.isEmpty())
        assertFalse(onlineState)
    }

    @Test
    fun `logout rejects HTTP redirects on portal endpoint`() {
        onlineState = true
        portalHttpCode = 302
        val cfg = SrunConfig(
            host = baseUrl,
            acId = "7",
            username = "20250001",
            password = "secret_password",
        )
        val err = assertThrows(IOException::class.java) { SrunClient().logout(cfg) }
        assertTrue(err.message.orEmpty().contains("HTTP 302"))
    }

    @Test
    fun `httpGetRaw scrubs IOException messages instead of leaking request URLs`() {
        val client = SrunClient(
            connectionFactory = { url ->
                throw IOException("Failed to connect to $url")
            },
        )
        val cfg = SrunConfig(
            host = baseUrl,
            acId = "7",
            username = "20250001",
            password = "secret_password",
        )
        val err = assertThrows(IOException::class.java) { client.login(cfg) }
        val msg = err.message.orEmpty()
        assertTrue(msg.contains("网关通信失败"))
        assertFalse(msg.contains("username"))
        assertFalse(msg.contains("20250001"))
        assertFalse(msg.contains("secret_password"))
    }

    @Test
    fun `oversized gateway response is rejected before parsing`() {
        statusBodyOverride = " ".repeat(1024 * 1024 + 1)
        val error = assertThrows(IllegalStateException::class.java) { SrunClient().getStatus(baseUrl) }
        assertTrue(error.message.orEmpty().contains("1 MiB"))
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split("&")
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) null else {
                    URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                        URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                }
            }
            .toMap()
    }

    private fun sendText(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
