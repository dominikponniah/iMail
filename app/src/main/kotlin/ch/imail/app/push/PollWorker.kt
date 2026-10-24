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
        val login = app.credentials.load() ?: return@withContext Result.success()
        try {
            val newer = ImapClient(login.account, login.password).listMessages("INBOX", 20)
                .filter { !it.seen && it.uid > app.settings.lastNotifiedUid }
            if (newer.isNotEmpty()) {
                app.settings.lastNotifiedUid = newer.maxOf { it.uid }
                Notifier.newMail(applicationContext, newer, hideContent = app.settings.appLockEnabled)
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
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
