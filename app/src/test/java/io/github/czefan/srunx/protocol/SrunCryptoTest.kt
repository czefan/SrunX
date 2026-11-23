package io.github.czefan.srunx.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SrunCryptoTest {

    @Test
    fun `matches reference srun_bx1 golden vectors`() {
        data class Vector(
            val username: String,
            val password: String,
            val ip: String,
            val acId: String,
            val challenge: String,
            val expectedHmd5: String,
            val expectedXHex: String,
            val expectedInfo: String,
            val expectedSha1: String,
        )

        val vectors = listOf(
            Vector(
                username = "1000000001",
                password = "123456",
                ip = "192.0.2.10",
                acId = "1",
                challenge = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                expectedHmd5 = "782ddd55ae4ad52adf58238f92a248c9",
                expectedXHex = "b6c982f957e18d3e8370e90f7ac6c142a5c422a962bff6d3c4f59c950b5f26c39dbc4dae28dcaa0b38ac981ef391bf33a0de99f10ac18010423f01f058d7c550f154294aca09ee368d9868dea57987d63eccc3a919cd358f92a9158d8d9741dde393a2bf",
                expectedInfo = "{SRBX1}fbUoQusTkFBJMyYgrbWV+dcP2dpXwAWF6gaMp+fsRbynwPIK80xdozXbUVEzYW4z/0B14+lVSVVogvNva0siHgiH8Hl8orDIk1T/7dueT5ZQz9y3CMtjke83iZI0ptNnDeyXwv==",
                expectedSha1 = "0fcfdcc89a24adb2258fcb7c711c76349ec84e4b",
            ),
            Vector(
                username = "20250001@cmcc",
                password = "p@ss\"w\\rd!",
                ip = "198.51.100.23",
                acId = "12",
                challenge = "deadbeef0123456789abcdef0123456789abcdef0123456789abcdef01234567",
                expectedHmd5 = "d7402d4184a594eb09c229f005ac9370",
                expectedXHex = "e43fe7b1f0284a8df588befe2d6eb22802b5e902fda5c9e77d9b9848696a600df075892d1e766411182cff3ff28fe5325d1d3bedefbc3d31812da96f0e386dd69895352b8d477901af920559e4ffb5d6910e5f07f60873d05badf729e8e50660151625c496077c5491b053954bf00f69",
                expectedInfo = "{SRBX1}eJAqbsL/m/7jXGEQGaBx8L8jB+G53Mqqs1KZmCpdZL7vnZYfNq1YPhSbAzAxkQHxchtEErQ4gFCVGOpwJkTfj3Xu0mK0h7YVle2iarFAfnOhJp4N5STztiKf5xq/e+1SihZp6RZNsimhbiyumALgO+==",
                expectedSha1 = "638501c88b90be9eb5a91b069b0416c31db2c26d",
            ),
            Vector(
                username = "a",
                password = "b",
                ip = "203.0.113.5",
                acId = "1",
                challenge = "shortkey",
                expectedHmd5 = "983f5dcd2b65f2e5d3ca7cc606649e9e",
                expectedXHex = "4d4435dba26f3837299c4ef5d4bd6322a01c19f34f33c503fa5ba8bd7d3c555740e86aea0633012cc71049c797f75fe56063adb957630335099ee9b52ddf99c0c3f273eb8268c8851ab69a0795b5d9037fdf16c691c921c1",
                expectedInfo = "{SRBX1}FH+jIBRwyJM3qPEjjGjk2dLMCs0g94HJQpK/wct4uunLBCldVk9VG9M+mMrc5jApZCyfKunkLzHRqKUjGnQ1v9gxMQKoO9XiClOOVeajI+0A76WCYMYTv+==",
                expectedSha1 = "7296c2d8fc95df4386df9cb473d6216e313c72ce",
            ),
        )

        for (v in vectors) {
            val hmd5 = SrunCrypto.hmacMd5Hex(v.challenge, v.password)
            assertEquals("hmd5 mismatch for ${v.username}", v.expectedHmd5, hmd5)

            val rawJson = "{\"username\":\"${v.username}\",\"password\":\"${
                v.password.replace("\\", "\\\\").replace("\"", "\\\"")
            }\",\"ip\":\"${v.ip}\",\"acid\":\"${v.acId}\",\"enc_ver\":\"srun_bx1\"}"
            val xHex = SrunCrypto.xencodeToBytes(rawJson, v.challenge)
                .joinToString("") { "%02x".format(it) }
            assertEquals("xencode mismatch for ${v.username}", v.expectedXHex, xHex)

            val info = SrunCrypto.encodeInfo(v.username, v.password, v.ip, v.acId, v.challenge)
            assertEquals("info mismatch for ${v.username}", v.expectedInfo, info)

            val sha1 = SrunCrypto.buildChecksum(v.challenge, v.username, hmd5, v.acId, v.ip, info)
            assertEquals("chksum mismatch for ${v.username}", v.expectedSha1, sha1)
        }
    }

    @Test
    fun `config helpers normalize host and domain suffix`() {
        val cfg = SrunConfig(
            host = " 192.0.2.1/ ",
            acId = " 1 ",
            username = " 20250001 ",
            password = "secret",
            domainSuffix = "@cmcc",
        )
        assertEquals("http://192.0.2.1", cfg.normalizedHost())
        assertEquals("20250001@cmcc", cfg.effectiveUsername())
        assertTrue(cfg.isReadyForLogin())

        val alreadySuffixed = cfg.copy(username = "20250001@unicom")
        assertEquals("20250001@unicom", alreadySuffixed.effectiveUsername())

        val incomplete = cfg.copy(password = "")
        assertFalse(incomplete.isReadyForLogin())
    }
}
