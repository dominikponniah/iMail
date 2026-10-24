package ch.imail.core.mail

import ch.imail.core.cache.MailCache
import ch.imail.core.cache.PendingAction
import ch.imail.core.cache.PendingQueue
import ch.imail.core.security.HeaderSanitizer
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.SendFailedException
import java.io.IOException
import javax.net.ssl.SSLException

/** DONE: applied on the server. QUEUED: server unreachable, saved locally and will be applied automatically. */
enum class Outcome { DONE, QUEUED }

data class FlushResult(val applied: Int, val remaining: Int)

/** A result that may come from the offline cache because the server could not be reached. */
data class Loaded<T>(val value: T, val offline: Boolean)

/**
 * Blocking facade for one account: talks to the server, keeps an encrypted offline copy and falls back to it when
 * the server is unreachable. Authentication errors are never masked by cached data.
 */
class MailService internal constructor(
    val account: Account,
    secret: SecretProvider,
    private val cache: MailCache?,
    private val queue: PendingQueue?,
    overrides: TlsOverrides?,
) {
    constructor(account: Account, secret: SecretProvider, cache: MailCache?, queue: PendingQueue? = null) : this(account, secret, cache, queue, null)
    internal constructor(account: Account, secret: SecretProvider, cache: MailCache?, overrides: TlsOverrides?) : this(account, secret, cache, null, overrides)

    private val imap = ImapClient(account, secret, overrides)
    private val smtp = SmtpClient(account, secret, overrides)

    fun testConnection() { imap.testConnection(); smtp.testConnection() }

    fun folders(): Loaded<List<MailFolder>> = online({ imap.listFolders().also { cache?.saveFolders(it) } }, { cache?.folders() })

    fun messages(folder: String, query: String? = null): Loaded<List<MessageSummary>> = online(
        { imap.listMessages(folder, 50, query).also { if (query.isNullOrBlank()) cache?.saveMessages(folder, it) } },
        {
            cache?.messages(folder)?.let { all ->
                if (query.isNullOrBlank()) all
                else all.filter { it.subject.contains(query, true) || it.from?.display?.contains(query, true) == true || it.from?.address?.contains(query, true) == true }
            }
        },
    )

    /** Returns the cached body if present (no network access), otherwise fetches and caches it. Flags are synced via [markSeen]. */
    fun message(folder: String, uid: Long): Loaded<MessageBody> {
        cache?.body(folder, uid)?.let { return Loaded(it, offline = false) }
        val fetched = imap.fetchMessage(folder, uid, markSeen = false)
        cache?.saveBody(folder, fetched)
        return Loaded(fetched, offline = false)
    }

    /** The local copy is always updated at once; the server flag is synced now or queued. */
    fun markSeen(folder: String, uid: Long, seen: Boolean): Outcome {
        cache?.setSeen(folder, uid, seen)
        return orQueue(PendingAction.Seen(folder, uid, seen)) { imap.setSeen(folder, uid, seen) }
    }

    fun attachment(folder: String, uid: Long, index: Int): ByteArray {
        cache?.attachment(folder, uid, index)?.let { return it }
        return imap.readAttachment(folder, uid, index).also { cache?.saveAttachment(folder, uid, index, it) }
    }

    fun delete(folder: String, uid: Long): Outcome {
        val r = orQueue(PendingAction.Delete(folder, uid)) { imap.delete(folder, uid) }
        cache?.remove(folder, uid) // gone locally either way
        return r
    }

    /**
     * Sends the mail, stores a copy in "Sent" and removes the draft it was written from, if any.
     * Invalid input is rejected immediately; with no connection the mail is queued and sent automatically later.
     */
    fun send(mail: OutgoingMail, draft: Pair<String, Long>? = null): Outcome {
        OutgoingMessageBuilder.build(jakarta.mail.Session.getInstance(java.util.Properties()), account, mail) // validate up front
        return orQueue(PendingAction.Send(mail, draft?.first, draft?.second)) { doSend(mail, draft) }
    }

    private fun doSend(mail: OutgoingMail, draft: Pair<String, Long>?) {
        val sent = smtp.send(mail)
        runCatching { imap.append(FolderRole.SENT, sent) }
        draft?.let { (folder, uid) -> runCatching { imap.delete(folder, uid); cache?.remove(folder, uid) } }
    }

    // --- offline queue -----------------------------------------------------------------------------------------------

    fun pendingCount(): Int = queue?.size() ?: 0

    /** Replays queued actions in order. Stops at the first connectivity or authentication problem; drops actions the server rejects. */
    @Synchronized fun flush(): FlushResult {
        val q = queue ?: return FlushResult(0, 0)
        var applied = 0
        while (true) {
            val next = q.all().firstOrNull() ?: break
            try {
                apply(next)
            } catch (e: AuthenticationFailedException) {
                throw e
            } catch (e: Exception) {
                if (isOffline(e)) break
                // permanently rejected (e.g. refused recipient, message already gone): do not retry forever
            }
            q.removeFirst(); applied++
        }
        return FlushResult(applied, q.size())
    }

    private fun apply(a: PendingAction) = when (a) {
        is PendingAction.Send -> doSend(a.mail, a.draftFolder?.let { f -> a.draftUid?.let { f to it } })
        is PendingAction.Delete -> imap.delete(a.folder, a.uid)
        is PendingAction.Seen -> imap.setSeen(a.folder, a.uid, a.seen)
    }

    private fun orQueue(action: PendingAction, run: () -> Unit): Outcome {
        val q = queue
        // Keep order: while earlier actions are waiting, new ones wait behind them.
        if (q != null && q.size() > 0) { q.add(action); return Outcome.QUEUED }
        return try {
            run(); Outcome.DONE
        } catch (e: AuthenticationFailedException) {
            throw e
        } catch (e: Exception) {
            if (q == null || !isOffline(e)) throw e
            q.add(action); Outcome.QUEUED
        }
    }

    /** Connectivity problems (no network, timeout, refused) - but not certificate errors, bad logins or rejected mail. */
    private fun isOffline(e: Throwable): Boolean {
        if (e is SendFailedException || e is SSLException) return false
        var t: Throwable? = e
        while (t != null) {
            if (t is SSLException) return false
            if (t is IOException) return true
            t = t.cause
        }
        return false
    }

    fun saveDraft(mail: OutgoingMail, replaceUid: Long? = null): Long? = imap.saveDraft(mail, replaceUid)

    /** Downloads bodies of the newest unread/recent messages so they can be read offline. Failures are ignored. */
    fun prefetch(folder: String, messages: List<MessageSummary>, limit: Int = 30) {
        val store = cache ?: return
        for (m in messages.take(limit)) {
            if (store.body(folder, m.uid) != null) continue
            try { store.saveBody(folder, imap.fetchMessage(folder, m.uid, markSeen = false)) } catch (_: Exception) { return }
        }
    }

    private fun <T> online(fetch: () -> T, fromCache: () -> T?): Loaded<T> = try {
        Loaded(fetch(), offline = false)
    } catch (e: AuthenticationFailedException) {
        throw e
    } catch (e: Exception) {
        Loaded(fromCache() ?: throw e, offline = true)
    }
}
