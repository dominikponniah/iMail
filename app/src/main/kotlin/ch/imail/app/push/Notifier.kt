package ch.imail.app.push

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import ch.imail.app.ImailApp
import ch.imail.app.MainActivity
import ch.imail.app.R
import ch.imail.core.mail.MessageSummary

object Notifier {
    private const val SERVICE_ID = 1
    private const val MAIL_ID = 2

    fun serviceNotification(ctx: Context): Notification =
        NotificationCompat.Builder(ctx, ImailApp.CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(ctx.getString(R.string.service_title))
            .setContentText(ctx.getString(R.string.service_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

    val serviceId get() = SERVICE_ID

    /** With the app lock active (or a locked screen) no sender/subject is revealed in the notification. */
    fun newMail(ctx: Context, mails: List<MessageSummary>, hideContent: Boolean) {
        if (mails.isEmpty() || Build_notificationsDenied(ctx)) return
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val generic = NotificationCompat.Builder(ctx, ImailApp.CHANNEL_MAIL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(ctx.getString(R.string.new_mail_hidden))
            .build()
        val first = mails.first()
        val title = if (hideContent) ctx.getString(R.string.new_mail_hidden)
        else if (mails.size == 1) first.from?.display ?: ctx.getString(R.string.new_mail_title)
        else ctx.getString(R.string.new_mails_title, mails.size)
        val text = if (hideContent) null else if (mails.size == 1) first.subject else mails.take(3).joinToString("\n") { "${it.from?.display.orEmpty()}: ${it.subject}" }
        val n = NotificationCompat.Builder(ctx, ImailApp.CHANNEL_MAIL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(text?.let { NotificationCompat.BigTextStyle().bigText(it) })
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(generic)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(MAIL_ID, n) } catch (_: SecurityException) { }
    }

    @Suppress("FunctionName")
    private fun Build_notificationsDenied(ctx: Context): Boolean =
        android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
}
