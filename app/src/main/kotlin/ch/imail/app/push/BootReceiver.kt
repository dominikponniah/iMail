package ch.imail.app.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts push after reboot or app update. Not exported; only system broadcasts reach it. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            runCatching { MailPushService.start(context) }
            PollWorker.schedule(context)
        }
    }
}
