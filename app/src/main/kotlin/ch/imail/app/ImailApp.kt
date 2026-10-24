package ch.imail.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import ch.imail.app.data.AppSettings
import ch.imail.app.data.CredentialStore
import ch.imail.app.data.DiskLogoCache
import ch.imail.core.logo.LogoFetcher
import ch.imail.core.logo.LogoService
import ch.imail.core.logo.MemoryLogoCache

class ImailApp : Application() {
    lateinit var credentials: CredentialStore
    lateinit var settings: AppSettings
    lateinit var logos: LogoService

    override fun onCreate() {
        super.onCreate()
        credentials = CredentialStore(this)
        settings = AppSettings(this)
        logos = LogoService(LogoFetcher(), listOf(MemoryLogoCache(), DiskLogoCache(this)))
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_MAIL, getString(R.string.channel_new_mail), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, getString(R.string.channel_service), NotificationManager.IMPORTANCE_MIN))
    }

    companion object {
        const val CHANNEL_MAIL = "new_mail"
        const val CHANNEL_SERVICE = "service"
    }
}
