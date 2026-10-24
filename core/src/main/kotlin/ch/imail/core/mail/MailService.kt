package ch.imail.core.mail

import ch.imail.core.cache.MailCache
import jakarta.mail.AuthenticationFailedException

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
    overrides: TlsOverrides?,
) {
    constructor(account: Account, secret: SecretProvider, cache: MailCache?) : this(account, secret, cache, null)

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

    fun markSeen(folder: String, uid: Long, seen: Boolean) {
        cache?.setSeen(folder, uid, seen)
        imap.setSeen(folder, uid, seen)
    }

    fun attachment(folder: String, uid: Long, index: Int): ByteArray {
        cache?.attachment(folder, uid, index)?.let { return it }
        return imap.readAttachment(folder, uid, index).also { cache?.saveAttachment(folder, uid, index, it) }
    }

    fun delete(folder: String, uid: Long) { imap.delete(folder, uid); cache?.remove(folder, uid) }

    /** Sends the mail, stores a copy in "Sent" and removes the draft it was written from, if any. */
    fun send(mail: OutgoingMail, draft: Pair<String, Long>? = null) {
        val sent = smtp.send(mail)
        runCatching { imap.append(FolderRole.SENT, sent) }
        draft?.let { (folder, uid) -> runCatching { delete(folder, uid) } }
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
