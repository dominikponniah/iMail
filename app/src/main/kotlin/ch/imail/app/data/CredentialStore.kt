package ch.imail.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ch.imail.core.cache.AesGcmCrypto
import ch.imail.core.cache.CacheCrypto
import ch.imail.core.mail.Account
import ch.imail.core.mail.AuthType
import ch.imail.core.mail.ConnectionSecurity
import ch.imail.core.mail.ServerConfig
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** [secret] is the password, or the serialized OAuth state (refresh token) for OAuth2 accounts. */
data class StoredAccount(val account: Account, val secret: String)

/**
 * Keeps all accounts encrypted with an AES-256-GCM key in the Android Keystore (non-exportable, hardware-backed where
 * available). Nothing is written in clear text; backups are disabled. The key does not require user authentication
 * because the push service must read it in the background; the optional app lock protects the UI instead.
 * A second Keystore key encrypts the offline mail cache.
 */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_accounts", Context.MODE_PRIVATE)
    private val lock = Any()

    fun all(): List<StoredAccount> = synchronized(lock) { readAll() }

    fun get(id: String): StoredAccount? = all().firstOrNull { it.account.id == id }

    fun upsert(account: Account, secret: String) = synchronized(lock) {
        val list = readAll().filterNot { it.account.id == account.id } + StoredAccount(account, secret)
        writeAll(list)
    }

    fun updateSecret(id: String, secret: String) = synchronized(lock) {
        writeAll(readAll().map { if (it.account.id == id) it.copy(secret = secret) else it })
    }

    fun remove(id: String) = synchronized(lock) { writeAll(readAll().filterNot { it.account.id == id }) }

    fun clearAll() = synchronized(lock) {
        prefs.edit().clear().apply()
        runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.apply { deleteEntry(CRED_ALIAS); deleteEntry(CACHE_ALIAS) } }
    }

    /** Encryption for the offline mail cache, bound to a separate Keystore key. */
    fun cacheCrypto(): CacheCrypto = AesGcmCrypto(key(CACHE_ALIAS))

    private fun readAll(): List<StoredAccount> {
        val blob = prefs.getString(KEY_BLOB, null) ?: return emptyList()
        return try {
            val arr = JSONArray(String(decrypt(Base64.decode(blob, Base64.NO_WRAP)), Charsets.UTF_8))
            List(arr.length()) { parse(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            prefs.edit().clear().apply() // unreadable (e.g. key lost after restore): start clean instead of crashing
            emptyList()
        }
    }

    private fun writeAll(list: List<StoredAccount>) {
        val arr = JSONArray().also { a -> list.forEach { a.put(serialize(it)) } }
        prefs.edit().putString(KEY_BLOB, Base64.encodeToString(encrypt(arr.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)).apply()
    }

    private fun serialize(s: StoredAccount) = JSONObject()
        .put("id", s.account.id).put("email", s.account.email).put("username", s.account.username).put("displayName", s.account.displayName)
        .put("auth", s.account.auth.name).put("provider", s.account.oauthProvider ?: JSONObject.NULL)
        .put("imap", server(s.account.imap)).put("smtp", server(s.account.smtp)).put("secret", s.secret)

    private fun parse(j: JSONObject) = StoredAccount(
        Account(
            email = j.getString("email"), username = j.getString("username"), displayName = j.optString("displayName"),
            imap = server(j.getJSONObject("imap")), smtp = server(j.getJSONObject("smtp")),
            id = j.getString("id"), auth = AuthType.valueOf(j.getString("auth")),
            oauthProvider = if (j.isNull("provider")) null else j.getString("provider"),
        ),
        j.getString("secret"),
    )

    private fun server(s: ServerConfig) = JSONObject().put("host", s.host).put("port", s.port).put("security", s.security.name)
    private fun server(o: JSONObject) = ServerConfig(o.getString("host"), o.getInt("port"), ConnectionSecurity.valueOf(o.getString("security")))

    private fun key(alias: String): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key(CRED_ALIAS)) }
        return c.iv + c.doFinal(plain)
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(CRED_ALIAS), GCMParameterSpec(128, data, 0, IV_BYTES)) }
        return c.doFinal(data, IV_BYTES, data.size - IV_BYTES)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val CRED_ALIAS = "imail_credentials_v2"
        const val CACHE_ALIAS = "imail_cache_v1"
        const val KEY_BLOB = "accounts"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
