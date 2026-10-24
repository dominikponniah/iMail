package ch.imail.app.push

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ch.imail.app.ImailApp
import ch.imail.core.mail.ImapClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Fallback for when the OS has stopped the IDLE service: checks the inbox every ~15 minutes. */
class PollWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as ImailApp
        var failed = false
        for (stored in app.credentials.all()) {
            val account = stored.account
            try {
                val newer = ImapClient(account, app.secretFor(account.id)).listMessages("INBOX", 20)
                    .filter { !it.seen && it.uid > app.settings.lastNotifiedUid(account.id) }
                if (newer.isNotEmpty()) {
                    app.settings.setLastNotifiedUid(account.id, newer.maxOf { it.uid })
                    Notifier.newMail(applicationContext, account, newer, hideContent = app.settings.appLockEnabled)
                }
            } catch (_: Exception) { failed = true }
        }
        if (failed) Result.retry() else Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<PollWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("mail_poll", ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancel(ctx: Context) { WorkManager.getInstance(ctx).cancelUniqueWork("mail_poll") }
    }
}
