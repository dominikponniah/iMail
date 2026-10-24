package ch.imail.app.data

import android.content.Context

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var appLockEnabled: Boolean
        get() = prefs.getBoolean("app_lock", false)
        set(v) = prefs.edit().putBoolean("app_lock", v).apply()

    var pushEnabled: Boolean
        get() = prefs.getBoolean("push", true)
        set(v) = prefs.edit().putBoolean("push", v).apply()

    /** Highest inbox UID already notified, so the fallback poller does not repeat notifications. */
    var lastNotifiedUid: Long
        get() = prefs.getLong("last_uid", 0L)
        set(v) = prefs.edit().putLong("last_uid", v).apply()
}
