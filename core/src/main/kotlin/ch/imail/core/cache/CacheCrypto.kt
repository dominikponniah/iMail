package ch.imail.core.cache

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts every cache file. Implemented with AES-256-GCM; on Android the key is a non-exportable Keystore key. */
interface CacheCrypto {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(data: ByteArray): ByteArray
}

class AesGcmCrypto(private val key: SecretKey, private val random: SecureRandom = SecureRandom()) : CacheCrypto {
    override fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION)
        // Keystore keys generate their own IV (randomized encryption is required); software keys get one from us.
        runCatching { c.init(Cipher.ENCRYPT_MODE, key) }.getOrElse {
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, ByteArray(IV_BYTES).also(random::nextBytes)))
        }
        return c.iv + c.doFinal(plain)
    }

    override fun decrypt(data: ByteArray): ByteArray {
        require(data.size > IV_BYTES) { "Cache-Datei beschädigt" }
        val c = Cipher.getInstance(TRANSFORMATION)
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, data, 0, IV_BYTES))
        return c.doFinal(data, IV_BYTES, data.size - IV_BYTES)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
