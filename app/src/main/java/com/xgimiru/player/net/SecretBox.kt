package com.xgimiru.player.net

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals short secrets (network share passwords) for storage. Nothing here logs a secret. */
interface SecretBox {
    /** Sealed text that is safe to store. Throws when the key is not available. */
    fun seal(plain: String): String

    /** The secret, or null when [sealed] is not ours or the key that sealed it is gone. */
    fun open(sealed: String): String?
}

/**
 * AES-256-GCM with a fresh IV per secret. Stored form: [PREFIX] + base64(IV length, IV, ciphertext with tag).
 * The key comes from [key]: the Android key store on a device, a plain key in the host tests.
 */
open class GcmBox(private val key: () -> SecretKey) : SecretBox {

    override fun seal(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(byteArrayOf(iv.size.toByte()) + iv + ct)
    }

    override fun open(sealed: String): String? = runCatching {
        if (!isSealed(sealed)) return null
        val b = Base64.getDecoder().decode(sealed.substring(PREFIX.length))
        val ivLen = b[0].toInt()
        if (ivLen !in 12..16 || b.size <= 1 + ivLen + TAG_BYTES) return null
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, b, 1, ivLen))
        String(c.doFinal(b, 1 + ivLen, b.size - 1 - ivLen), Charsets.UTF_8)
    }.getOrNull()

    companion object {
        const val PREFIX = "k1:"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val TAG_BYTES = 16

        fun isSealed(s: String) = s.startsWith(PREFIX)
    }
}

/** The key lives in the AndroidKeyStore and never leaves it: a copy of the app data is useless on another device. */
class KeystoreBox(alias: String = ALIAS) : GcmBox({ keyOf(alias) }) {
    companion object {
        const val ALIAS = "lumen_play_shares"

        @Synchronized
        private fun keyOf(alias: String): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey(alias, null) as? SecretKey)?.let { return it }
            val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            g.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            return g.generateKey()
        }
    }
}
