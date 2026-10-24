package ch.imail.core.mail

import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
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

class AttachmentTooLargeException : Exception("Anhang zu gross")

/** Blocking IMAP operations. Each call opens its own short-lived connection; call from a background thread. */
class ImapClient internal constructor(
    private val account: Account,
    private val password: String,
    private val overrides: TlsOverrides?,
) {
    constructor(account: Account, password: String) : this(account, password, null)

    internal fun connect(): Store {
        val store = MailSession.create("imap", account.imap, overrides).getStore("imap")
        store.connect(account.imap.host, account.imap.port, account.username, password)
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
            val role = when {
                f.fullName.equals("INBOX", true) -> FolderRole.INBOX
                "\\Sent" in attrs -> FolderRole.SENT
                "\\Drafts" in attrs -> FolderRole.DRAFTS
                "\\Trash" in attrs -> FolderRole.TRASH
                "\\Archive" in attrs || "\\All" in attrs -> FolderRole.ARCHIVE
                "\\Junk" in attrs -> FolderRole.JUNK
                else -> FolderRole.OTHER
            }
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

    fun fetchMessage(folderName: String, uid: Long): MessageBody = withFolder(folderName, readWrite = true) { folder ->
        val m = folder.getMessageByUID(uid) as? MimeMessage ?: throw NoSuchElementException("Nachricht nicht gefunden")
        val parts = MessageParts.parse(m)
        m.setFlag(Flags.Flag.SEEN, true)
        fun addrs(t: Message.RecipientType) = (m.getRecipients(t) ?: emptyArray()).filterIsInstance<InternetAddress>().map { MailAddress(it.personal, it.address ?: "") }
        MessageBody(
            summary = toSummary(folder, m).copy(seen = true),
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

    /** Moves to the trash folder (copy + delete) or deletes permanently when already in trash or none exists. */
    fun delete(folderName: String, uid: Long) = withStore { store ->
        val trash = store.defaultFolder.list("*").map { it as IMAPFolder }.firstOrNull { "\\Trash" in it.attributes }
        val folder = store.getFolder(folderName) as IMAPFolder
        folder.open(Folder.READ_WRITE)
        try {
            val m = folder.getMessageByUID(uid) ?: return@withStore
            if (trash != null && trash.fullName != folder.fullName) folder.copyMessages(arrayOf(m), trash)
            m.setFlag(Flags.Flag.DELETED, true)
            folder.expunge()
        } finally { runCatching { folder.close(true) } }
    }

    /** Stores a message (sent copy or draft) in the folder with the given role, if one exists. */
    fun append(role: FolderRole, message: MimeMessage, seen: Boolean = true) = withStore { store ->
        val attr = when (role) { FolderRole.SENT -> "\\Sent"; FolderRole.DRAFTS -> "\\Drafts"; else -> return@withStore }
        val target = store.defaultFolder.list("*").map { it as IMAPFolder }.firstOrNull { attr in it.attributes } ?: return@withStore
        message.setFlag(Flags.Flag.SEEN, seen)
        message.setFlag(Flags.Flag.DRAFT, role == FolderRole.DRAFTS)
        target.appendMessages(arrayOf(message))
    }

    companion object { private const val PREVIEW_MAX_BYTES = 300_000 }
}
