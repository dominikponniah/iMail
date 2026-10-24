package ch.imail.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import ch.imail.app.data.AppSettings
import ch.imail.app.data.CredentialStore
import ch.imail.app.data.DiskLogoCache
import ch.imail.app.data.OAuthManager
import ch.imail.core.cache.MailCache
import ch.imail.core.cache.PendingQueue
import ch.imail.core.logo.LogoFetcher
import ch.imail.core.logo.LogoService
import ch.imail.core.logo.MemoryLogoCache
import ch.imail.core.mail.AuthType
import ch.imail.core.mail.MailService
import ch.imail.core.mail.SecretProvider
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class ImailApp : Application() {
    lateinit var credentials: CredentialStore
    lateinit var settings: AppSettings
    lateinit var logos: LogoService
    lateinit var oauth: OAuthManager
    private val services = ConcurrentHashMap<String, MailService>()

    override fun onCreate() {
        super.onCreate()
        credentials = CredentialStore(this)
        settings = AppSettings(this)
        oauth = OAuthManager(this, credentials)
        logos = LogoService(LogoFetcher(), listOf(MemoryLogoCache(), DiskLogoCache(this)))
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_MAIL, getString(R.string.channel_new_mail), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN))
    }

    /** Password or fresh OAuth token for the account; evaluated on every connection. */
    fun secretFor(accountId: String): SecretProvider {
        val stored = credentials.get(accountId) ?: throw IllegalStateException("Konto nicht gefunden")
        return if (stored.account.auth == AuthType.OAUTH2) oauth.tokenProvider(accountId) else SecretProvider { credentials.get(accountId)?.secret.orEmpty() }
    }

    fun cacheDir(accountId: String) = File(filesDir, "mailcache/$accountId")

    /** One service (server access + encrypted offline cache) per account. */
    fun service(accountId: String): MailService? {
        services[accountId]?.let { return it }
        val stored = credentials.get(accountId) ?: return null
        return MailService(
            stored.account, secretFor(accountId),
            MailCache(cacheDir(accountId), credentials.cacheCrypto()),
            PendingQueue(java.io.File(cacheDir(accountId), "outbox.bin"), credentials.cacheCrypto()),
        )
            .also { services[accountId] = it }
    }

    fun forgetAccount(accountId: String) {
        services.remove(accountId)
        cacheDir(accountId).deleteRecursively()
        settings.forget(accountId)
    }

    companion object {
        const val CHANNEL_MAIL = "new_mail"
        const val CHANNEL_SERVICE = "service"
    }
}
