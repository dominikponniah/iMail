package ch.imail.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ch.imail.core.mail.Account
import ch.imail.core.mail.ConnectionSecurity
import ch.imail.core.mail.ServerConfig
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class StoredLogin(val account: Account, val password: String)

/**
 * Keeps the account and password encrypted with an AES-256-GCM key that lives in the Android Keystore
 * (non-exportable, hardware-backed where available). Nothing is written in clear text; backups are disabled.
 * The key does not require user authentication because the push service must read it in the background;
 * the optional app lock protects the UI instead.
 */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_login", Context.MODE_PRIVATE)

    fun load(): StoredLogin? {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        return try {
            val json = JSONObject(String(decrypt(Base64.decode(blob, Base64.NO_WRAP)), Charsets.UTF_8))
            StoredLogin(account(json), json.getString("password"))
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun save(account: Account, password: String) {
        val json = JSONObject()
            .put("email", account.email).put("username", account.username).put("displayName", account.displayName)
            .put("imap", server(account.imap)).put("smtp", server(account.smtp)).put("password", password)
        val encrypted = encrypt(json.toString().toByteArray(Charsets.UTF_8))
        prefs.edit().putString(KEY_BLOB, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
        runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS) }
    }

    private fun server(s: ServerConfig) = JSONObject().put("host", s.host).put("port", s.port).put("security", s.security.name)
    private fun server(o: JSONObject) = ServerConfig(o.getString("host"), o.getInt("port"), ConnectionSecurity.valueOf(o.getString("security")))
    private fun account(j: JSONObject) = Account(j.getString("email"), j.getString("username"), server(j.getJSONObject("imap")), server(j.getJSONObject("smtp")), j.optString("displayName"))

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(plain)
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_BYTES)) }
        return c.doFinal(data, IV_BYTES, data.size - IV_BYTES)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "imail_credentials_v1"
        const val KEY_BLOB = "blob"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
