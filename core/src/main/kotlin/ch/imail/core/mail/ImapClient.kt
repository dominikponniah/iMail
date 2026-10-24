package ch.imail.core.mail

import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.search.FromStringTerm
import jakarta.mail.search.OrTerm
import jakarta.mail.search.SubjectTerm
import org.eclipse.angus.mail.imap.IMAPFolder
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Properties

class AttachmentTooLargeException : Exception("Anhang zu gross")

/** Blocking IMAP operations. Each call opens its own short-lived connection; call from a background thread. */
class ImapClient internal constructor(
    private val account: Account,
    private val secret: SecretProvider,
    private val overrides: TlsOverrides?,
) {
    constructor(account: Account, password: String) : this(account, SecretProvider { password }, null)
    constructor(account: Account, secret: SecretProvider) : this(account, secret, null)
    internal constructor(account: Account, password: String, overrides: TlsOverrides?) : this(account, SecretProvider { password }, overrides)

    internal fun connect(): Store {
        val store = MailSession.create("imap", account.imap, overrides, account.auth).getStore("imap")
        store.connect(account.imap.host, account.imap.port, account.username, secret.get())
        return store
    }

    private inline fun <T> withStore(block: (Store) -> T): T {
        val store = connect()
        try { return block(store) } finally { runCatching { store.close() } }
    }

    private inline fun <T> withFolder(name: String, readWrite: Boolean, block: (IMAPFolder) -> T): T = withStore { store ->
        val folder = store.getFolder(name) as IMAPFolder
        folder.open(if (readWrite) Folder.READ_WRITE else Folder.READ_ONLY)
        try { block(folder) } finally { runCatching { folder.close(readWrite) } }
    }

    /** Verifies host, TLS and credentials without reading mail. */
    fun testConnection() = withStore { }

    fun listFolders(): List<MailFolder> = withStore { store ->
        store.defaultFolder.list("*").map { f ->
            f as IMAPFolder
            val attrs = f.attributes.toSet()
            val role = roleOf(f.fullName, f.name, attrs)
            val selectable = "\\Noselect" !in attrs
            MailFolder(f.fullName, f.name, role, if (selectable) runCatching { f.unreadMessageCount }.getOrDefault(0) else 0)
        }.sortedBy { it.role.ordinal }
    }

    fun listMessages(folderName: String, limit: Int = 50, query: String? = null): List<MessageSummary> =
        withFolder(folderName, readWrite = false) { folder ->
            val found: Array<Message> = if (query.isNullOrBlank()) {
                val total = folder.messageCount
                if (total == 0) emptyArray() else folder.getMessages((total - limit + 1).coerceAtLeast(1), total)
            } else {
                val term = OrTerm(SubjectTerm(query), FromStringTerm(query))
                folder.search(term).takeLast(limit).toTypedArray()
            }
            prefetch(folder, found)
            found.reversed().map { toSummary(folder, it) }
        }

    internal fun prefetch(folder: IMAPFolder, msgs: Array<Message>) {
        val fp = FetchProfile().apply {
            add(FetchProfile.Item.ENVELOPE)
            add(FetchProfile.Item.FLAGS)
            add(FetchProfile.Item.CONTENT_INFO)
            add(UIDFolder.FetchProfileItem.UID)
        }
        folder.fetch(msgs, fp)
    }

    internal fun toSummary(folder: IMAPFolder, m: Message): MessageSummary {
        val mime = m as MimeMessage
        val from = (m.from?.firstOrNull() as? InternetAddress)?.let { MailAddress(it.personal, it.address ?: "") }
        val parts = if (m.size in 0..PREVIEW_MAX_BYTES) runCatching { MessageParts.parse(mime) }.getOrNull() else null
        val preview = (parts?.text ?: parts?.html?.let { org.jsoup.Jsoup.parse(it).text() }).orEmpty()
            .replace(Regex("\\s+"), " ").trim().take(160)
        return MessageSummary(
            uid = folder.getUID(m),
            from = from,
            subject = m.subject.orEmpty(),
            preview = preview,
            dateMs = (m.receivedDate ?: m.sentDate)?.time ?: 0L,
            seen = m.isSet(Flags.Flag.SEEN),
            hasAttachments = m.isMimeType("multipart/mixed"),
        )
    }

    fun fetchMessage(folderName: String, uid: Long, markSeen: Boolean = true): MessageBody = withFolder(folderName, readWrite = markSeen) { folder ->
        val m = folder.getMessageByUID(uid) as? MimeMessage ?: throw NoSuchElementException("Nachricht nicht gefunden")
        val parts = MessageParts.parse(m)
        if (markSeen) m.setFlag(Flags.Flag.SEEN, true)
        fun addrs(t: Message.RecipientType) = (m.getRecipients(t) ?: emptyArray()).filterIsInstance<InternetAddress>().map { MailAddress(it.personal, it.address ?: "") }
        MessageBody(
            summary = toSummary(folder, m).let { if (markSeen) it.copy(seen = true) else it },
            to = addrs(Message.RecipientType.TO),
            cc = addrs(Message.RecipientType.CC),
            messageId = m.messageID,
            references = m.getHeader("References", " "),
            html = parts.html,
            text = parts.text,
            attachments = parts.attachments,
        )
    }

    /** Reads one attachment fully, refusing anything above [maxBytes]. */
    fun readAttachment(folderName: String, uid: Long, index: Int, maxBytes: Int = 25 * 1024 * 1024): ByteArray =
        withFolder(folderName, readWrite = false) { folder ->
            val m = folder.getMessageByUID(uid) as MimeMessage
            val part = MessageParts.parse(m).attachmentParts[index]
            if (part.size > maxBytes) throw AttachmentTooLargeException()
            part.inputStream.use { readLimited(it, maxBytes) }
        }

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > max) throw AttachmentTooLargeException()
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun setSeen(folderName: String, uid: Long, seen: Boolean) = withFolder(folderName, true) { f ->
        f.getMessageByUID(uid)?.setFlag(Flags.Flag.SEEN, seen)
    }

    /** Moves to the trash folder (copy + delete) or deletes permanently when already in trash, in drafts, or no trash exists. */
    fun delete(folderName: String, uid: Long) = withStore { store ->
        val trash = findFolder(store, FolderRole.TRASH)
        val folder = store.getFolder(folderName) as IMAPFolder
        val permanent = roleOf(folder.fullName, folder.name, folder.attributes.toSet()) == FolderRole.DRAFTS
        folder.open(Folder.READ_WRITE)
        try {
            val m = folder.getMessageByUID(uid) ?: return@withStore
            if (!permanent && trash != null && trash.fullName != folder.fullName) folder.copyMessages(arrayOf(m), trash)
            m.setFlag(Flags.Flag.DELETED, true)
            folder.expunge()
        } finally { runCatching { folder.close(true) } }
    }

    /** Stores a copy of a sent message in the "Sent" folder, creating it if the server has none. */
    fun append(role: FolderRole, message: MimeMessage, seen: Boolean = true) = withStore { store ->
        if (role != FolderRole.SENT) return@withStore
        val target = findOrCreate(store, role) ?: return@withStore
        message.setFlag(Flags.Flag.SEEN, seen)
        target.appendMessages(arrayOf(message))
    }

    /**
     * Saves a draft in the server's Drafts folder (flag \Draft) so it appears on all devices.
     * [replaceUid] is an earlier version of the same draft that is removed afterwards. Returns the new UID if it could be determined.
     */
    fun saveDraft(mail: OutgoingMail, replaceUid: Long? = null): Long? = withStore { store ->
        val msg = OutgoingMessageBuilder.build(Session.getInstance(Properties()), account, mail, allowEmptyRecipients = true)
        msg.setFlag(Flags.Flag.DRAFT, true)
        msg.setFlag(Flags.Flag.SEEN, true)
        val drafts = findOrCreate(store, FolderRole.DRAFTS) ?: throw IllegalStateException("Kein Entwürfe-Ordner")
        drafts.open(Folder.READ_WRITE)
        try {
            val appended = runCatching { drafts.appendUIDMessages(arrayOf(msg)) }.getOrNull()
            val newUid = appended?.firstOrNull()?.uid ?: drafts.messageCount.takeIf { it > 0 }?.let { drafts.getUID(drafts.getMessage(it)) }
            replaceUid?.let { old -> drafts.getMessageByUID(old)?.setFlag(Flags.Flag.DELETED, true); drafts.expunge() }
            newUid
        } finally { runCatching { drafts.close(true) } }
    }

    private fun findFolder(store: Store, role: FolderRole): IMAPFolder? =
        store.defaultFolder.list("*").map { it as IMAPFolder }.firstOrNull { roleOf(it.fullName, it.name, it.attributes.toSet()) == role }

    private fun findOrCreate(store: Store, role: FolderRole): IMAPFolder? {
        findFolder(store, role)?.let { return it }
        val name = when (role) { FolderRole.SENT -> "Sent"; FolderRole.DRAFTS -> "Drafts"; else -> return null }
        return (store.getFolder(name) as IMAPFolder).also { if (!it.exists()) it.create(Folder.HOLDS_MESSAGES) }
    }

    companion object {
        private const val PREVIEW_MAX_BYTES = 300_000

        private val names = mapOf(
            FolderRole.SENT to setOf("sent", "sent items", "sent messages", "gesendet", "gesendete objekte", "gesendete elemente"),
            FolderRole.DRAFTS to setOf("drafts", "draft", "entwürfe", "entwuerfe"),
            FolderRole.TRASH to setOf("trash", "deleted items", "deleted messages", "papierkorb", "gelöscht", "geloescht"),
            FolderRole.JUNK to setOf("junk", "spam", "junk e-mail", "junk email"),
            FolderRole.ARCHIVE to setOf("archive", "archiv", "all mail"),
        )

        /** Role from the server's SPECIAL-USE attributes, falling back to common folder names. */
        internal fun roleOf(fullName: String, name: String, attrs: Set<String>): FolderRole = when {
            fullName.equals("INBOX", true) -> FolderRole.INBOX
            "\\Sent" in attrs -> FolderRole.SENT
            "\\Drafts" in attrs -> FolderRole.DRAFTS
            "\\Trash" in attrs -> FolderRole.TRASH
            "\\Archive" in attrs || "\\All" in attrs -> FolderRole.ARCHIVE
            "\\Junk" in attrs -> FolderRole.JUNK
            else -> names.entries.firstOrNull { name.lowercase() in it.value }?.key ?: FolderRole.OTHER
        }
    }
}
