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

    var activeAccountId: String?
        get() = prefs.getString("active_account", null)
        set(v) = prefs.edit().putString("active_account", v).apply()

    /** Highest inbox UID already notified per account, so the fallback poller does not repeat notifications. */
    fun lastNotifiedUid(accountId: String): Long = prefs.getLong("last_uid_$accountId", 0L)
    fun setLastNotifiedUid(accountId: String, uid: Long) = prefs.edit().putLong("last_uid_$accountId", uid).apply()
    fun forget(accountId: String) = prefs.edit().remove("last_uid_$accountId").apply()
}
