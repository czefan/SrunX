package io.github.czefan.srunx.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 基于 Android Keystore (AES-256-GCM) 的本地凭据加解密工具，零第三方加密库依赖。
 */
object KeystoreCrypto {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "srunx_pwd_aes_gcm"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BITS = 128

    private val lock = Any()

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        return synchronized(lock) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(1 + iv.size + cipherBytes.size)
            combined[0] = iv.size.toByte()
            iv.copyInto(combined, destinationOffset = 1)
            cipherBytes.copyInto(combined, destinationOffset = 1 + iv.size)
            Base64.getEncoder().encodeToString(combined)
        }
    }

    fun decrypt(encoded: String): String {
        if (encoded.isBlank()) return ""
        return synchronized(lock) {
            runCatching {
                val combined = Base64.getDecoder().decode(encoded)
                if (combined.size < 2) return@runCatching ""
                val ivLen = combined[0].toInt() and 0xFF
                if (ivLen <= 0 || combined.size <= 1 + ivLen) return@runCatching ""
                val iv = combined.copyOfRange(1, 1 + ivLen)
                val cipherBytes = combined.copyOfRange(1 + ivLen, combined.size)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    getOrCreateKey(),
                    GCMParameterSpec(TAG_LENGTH_BITS, iv),
                )
                String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
            }.getOrDefault("")
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
