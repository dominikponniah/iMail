package ch.imail.app.push

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ch.imail.app.ImailApp
import ch.imail.core.mail.ImapClient
import ch.imail.core.mail.ImapIdleWatcher

/**
 * Foreground service that keeps an IMAP IDLE connection open and posts a notification per new message.
 * No Google/FCM and no third-party server: mail metadata only ever travels between the device and the user's mail server.
 */
class MailPushService : Service() {
    private class Watch(val watcher: ImapIdleWatcher, val thread: Thread)
    private val watches = mutableListOf<Watch>()
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var registered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        // A network change silently kills the TCP connections; drop them so the watchers reconnect immediately.
        private var first = true
        override fun onAvailable(network: Network) {
            if (first) { first = false; return } // fires once on registration
            restartWatchers()
            val app = application as ImailApp
            Thread { app.credentials.all().forEach { runCatching { app.service(it.account.id)?.flush() } } }.start() // send what was queued offline
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as ImailApp
        if (!app.settings.pushEnabled || app.credentials.all().isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, Notifier.serviceId, Notifier.serviceNotification(this),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        restartWatchers()
        if (!registered) { connectivity.registerDefaultNetworkCallback(networkCallback); registered = true }
        return START_STICKY
    }

    /** One IDLE connection per account. */
    @Synchronized private fun restartWatchers() {
        stopWatchers()
        val app = application as ImailApp
        for (stored in app.credentials.all()) {
            val account = stored.account
            val w = ImapIdleWatcher(
                client = ImapClient(account, app.secretFor(account.id)),
                onNewMail = { mails ->
                    app.settings.setLastNotifiedUid(account.id, maxOf(app.settings.lastNotifiedUid(account.id), mails.maxOf { it.uid }))
                    Notifier.newMail(this, account, mails, hideContent = app.settings.appLockEnabled)
                },
            )
            watches += Watch(w, Thread(w::run, "imail-idle-${account.id.take(6)}").apply { isDaemon = true; start() })
        }
    }

    private fun stopWatchers() {
        watches.forEach { it.watcher.stop() }
        watches.forEach { it.thread.join(2000) }
        watches.clear()
    }

    override fun onDestroy() {
        if (registered) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        stopWatchers()
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            if ((ctx.applicationContext as ImailApp).settings.pushEnabled)
                ContextCompat.startForegroundService(ctx, Intent(ctx, MailPushService::class.java))
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, MailPushService::class.java)) }

        /** Picks up added/removed accounts. */
        fun restart(ctx: Context) { stop(ctx); start(ctx) }
    }
}
