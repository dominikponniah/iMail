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
    private var watcher: ImapIdleWatcher? = null
    private var thread: Thread? = null
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        // A network change silently kills the TCP connection; drop it so the watcher reconnects immediately.
        private var first = true
        override fun onAvailable(network: Network) {
            if (first) { first = false; return } // fires once on registration
            restartWatcher()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as ImailApp
        if (!app.settings.pushEnabled || app.credentials.load() == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, Notifier.serviceId, Notifier.serviceNotification(this),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (thread == null) {
            startWatcher()
            connectivity.registerDefaultNetworkCallback(networkCallback)
        }
        return START_STICKY
    }

    private fun startWatcher() {
        val app = application as ImailApp
        val login = app.credentials.load() ?: return stopSelf()
        val w = ImapIdleWatcher(
            client = ImapClient(login.account, login.password),
            onNewMail = { mails ->
                app.settings.lastNotifiedUid = maxOf(app.settings.lastNotifiedUid, mails.maxOf { it.uid })
                Notifier.newMail(this, mails, hideContent = app.settings.appLockEnabled)
            },
        )
        watcher = w
        thread = Thread(w::run, "imail-idle").apply { isDaemon = true; start() }
    }

    @Synchronized private fun restartWatcher() {
        watcher?.stop()
        thread?.join(2000)
        if (!Thread.currentThread().isInterrupted) startWatcher()
    }

    override fun onDestroy() {
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        watcher?.stop()
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            if ((ctx.applicationContext as ImailApp).settings.pushEnabled)
                ContextCompat.startForegroundService(ctx, Intent(ctx, MailPushService::class.java))
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, MailPushService::class.java)) }
    }
}
