package io.github.czefan.srunx.protocol

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.floor

/**
 * 深澜（SRun3000）srun_bx1 认证加密算法实现。
 * 严格对齐抓包前端 JS 与 Python 参考实现，全部使用无符号右移（ushr）与字节数组直出。
 */
object SrunCrypto {
    const val SRUN_ALPHA = "LVoJPiCN2R8G90yg+hmFHuacZ1OWMnrsSTXkYpUq/3dlbfKwv6xztjI7DeBE45QA"
    const val STD_ALPHA = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    const val N_CONST = "200"
    const val TYPE_CONST = "1"
    const val ENC_VER = "srun_bx1"

    private val b64TranslateTable: Map<Char, Char> =
        STD_ALPHA.indices.associate { idx -> STD_ALPHA[idx] to SRUN_ALPHA[idx] }

    private fun stringToInts(msg: String, includeLen: Boolean): IntArray {
        val len = msg.length
        val wordCount = (len + 3) / 4
        val result = IntArray(if (includeLen) wordCount + 1 else wordCount)
        var outIdx = 0
        var i = 0
        while (i < len) {
            val b0 = msg[i].code and 0xFF
            val b1 = if (i + 1 < len) msg[i + 1].code and 0xFF else 0
            val b2 = if (i + 2 < len) msg[i + 2].code and 0xFF else 0
            val b3 = if (i + 3 < len) msg[i + 3].code and 0xFF else 0
            result[outIdx++] = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            i += 4
        }
        if (includeLen) {
            result[outIdx] = len
        }
        return result
    }

    private fun intsToBytes(arr: IntArray): ByteArray {
        val out = ByteArray(arr.size * 4)
        for (i in arr.indices) {
            val v = arr[i]
            val offset = i * 4
            out[offset] = (v and 0xFF).toByte()
            out[offset + 1] = ((v ushr 8) and 0xFF).toByte()
            out[offset + 2] = ((v ushr 16) and 0xFF).toByte()
            out[offset + 3] = ((v ushr 24) and 0xFF).toByte()
        }
        return out
    }

    fun xencodeToBytes(msg: String, key: String): ByteArray {
        if (msg.isEmpty()) return ByteArray(0)
        val pwd = stringToInts(msg, includeLen = true)
        val rawKey = stringToInts(key, includeLen = false)
        val pwdk = if (rawKey.size < 4) {
            IntArray(4).also { rawKey.copyInto(it) }
        } else {
            rawKey
        }

        val n = pwd.size - 1
        var z = pwd[n]
        val c = 0x86014019.toInt() or 0x183639A0
        var q = floor(6.0 + 52.0 / (n + 1)).toInt()
        var d = 0

        while (q > 0) {
            d += c
            val e = (d ushr 2) and 3
            for (p in 0 until n) {
                val y = pwd[p + 1]
                val m = ((z ushr 5) xor (y shl 2)) +
                    (((y ushr 3) xor (z shl 4)) xor (d xor y)) +
                    (pwdk[(p and 3) xor e] xor z)
                z = pwd[p] + m
                pwd[p] = z
            }
            val y = pwd[0]
            val m = ((z ushr 5) xor (y shl 2)) +
                (((y ushr 3) xor (z shl 4)) xor (d xor y)) +
                (pwdk[(n and 3) xor e] xor z)
            z = pwd[n] + m
            pwd[n] = z
            q--
        }

        return intsToBytes(pwd)
    }

    fun customBase64(bytes: ByteArray): String {
        val standard = Base64.getEncoder().encodeToString(bytes)
        val sb = StringBuilder(standard.length)
        for (ch in standard) {
            sb.append(b64TranslateTable[ch] ?: ch)
        }
        return sb.toString()
    }

    fun encodeInfo(
        username: String,
        password: String,
        ip: String,
        acId: String,
        challenge: String,
    ): String {
        val rawJson = buildString {
            append("{\"username\":\"")
            appendJsonEscaped(username)
            append("\",\"password\":\"")
            appendJsonEscaped(password)
            append("\",\"ip\":\"")
            appendJsonEscaped(ip)
            append("\",\"acid\":\"")
            appendJsonEscaped(acId)
            append("\",\"enc_ver\":\"")
            appendJsonEscaped(ENC_VER)
            append("\"}")
        }
        val encrypted = xencodeToBytes(rawJson, challenge)
        return "{SRBX1}${customBase64(encrypted)}"
    }

    fun hmacMd5Hex(key: String, msg: String): String {
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacMD5"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8)).toHexString()
    }

    fun sha1Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        return digest.digest(input.toByteArray(Charsets.UTF_8)).toHexString()
    }

    fun buildChecksum(
        challenge: String,
        username: String,
        hmd5: String,
        acId: String,
        ip: String,
        info: String,
    ): String {
        val chkStr = buildString {
            append(challenge).append(username)
            append(challenge).append(hmd5)
            append(challenge).append(acId)
            append(challenge).append(ip)
            append(challenge).append(N_CONST)
            append(challenge).append(TYPE_CONST)
            append(challenge).append(info)
        }
        return sha1Hex(chkStr)
    }

    private fun ByteArray.toHexString(): String {
        val hexChars = CharArray(size * 2)
        val digits = "0123456789abcdef"
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            hexChars[i * 2] = digits[v ushr 4]
            hexChars[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(hexChars)
    }

    private fun StringBuilder.appendJsonEscaped(value: String) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                    } else {
                        append(ch)
                    }
                }
            }
        }
    }
}
