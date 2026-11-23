package io.github.czefan.srunx.protocol

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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
    private var returnNonSrunOnStatus = false

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
            if (returnNonSrunOnStatus) {
                sendText(ex, 200, """{"status":"unrelated"}""")
                return@createContext
            }
            val cb = parseQuery(ex.requestURI.rawQuery)["callback"] ?: "cb"
            ex.responseHeaders.add("Srun-Server", "SRunCGIAuthIntfSvr V1.18")
            val body = if (onlineState) {
                """$cb({"error":"ok","user_name":"20250001@cmcc","products_name":"学生","online_ip":"$testClientIp","user_mac":"aa:bb:cc:dd:ee:ff","all_bytes":12345678,"sum_bytes":9876543210,"online_device_total":"2","online_device_detail":"{\"1\":{\"os_name\":\"AndroidOS\",\"ip\":\"$testClientIp\",\"rad_online_id\":\"1\"},\"2\":{\"os_name\":\"Windows 10\",\"ip\":\"192.0.2.11\",\"rad_online_id\":\"2\"}}"})"""
            } else {
                """$cb({"error":"not_online_error","error_msg":"","client_ip":"$testClientIp","srun_ver":"SRunCGIAuthIntfSvr V1.18"})"""
            }
            sendText(ex, 200, body)
        }

        server.createContext("/cgi-bin/get_challenge") { ex ->
            val cb = parseQuery(ex.requestURI.rawQuery)["callback"] ?: "cb"
            ex.responseHeaders.add("Srun-Server", "SRunCGIAuthIntfSvr V1.18")
            val body = """$cb({"challenge":"$testChallenge","client_ip":"$testClientIp","online_ip":"$testClientIp","error":"ok","res":"ok"})"""
            sendText(ex, 200, body)
        }

        server.createContext("/cgi-bin/srun_portal") { ex ->
            val params = parseQuery(ex.requestURI.rawQuery)
            val cb = params["callback"] ?: "cb"
            ex.responseHeaders.add("Srun-Server", "SRunCGIAuthIntfSvr V1.18")
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
    fun `getStatus flags non-srun server properly`() {
        returnNonSrunOnStatus = true
        val status = SrunClient().getStatus(baseUrl)
        assertFalse(status.isSrunServer)
        assertFalse(status.isOnline)
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
