package ch.imail.core.cache

import ch.imail.core.mail.OutgoingAttachment
import ch.imail.core.mail.OutgoingMail
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** An action the user performed while the server was unreachable; replayed in order once a connection works. */
sealed interface PendingAction {
    data class Send(val mail: OutgoingMail, val draftFolder: String?, val draftUid: Long?) : PendingAction
    data class Delete(val folder: String, val uid: Long) : PendingAction
    data class Seen(val folder: String, val uid: Long, val seen: Boolean) : PendingAction
}

/** Encrypted, order-preserving outbox for one account. One file, rewritten atomically after every change. */
class PendingQueue(private val file: File, private val crypto: CacheCrypto) {
    private val lock = Any()

    fun all(): List<PendingAction> = synchronized(lock) { load() }
    fun size(): Int = all().size

    fun add(action: PendingAction) = synchronized(lock) { save(load() + action) }

    /** Removes the first (oldest) entry after it was applied successfully. */
    fun removeFirst() = synchronized(lock) { save(load().drop(1)) }

    private fun load(): List<PendingAction> {
        if (!file.isFile) return emptyList()
        return try {
            DataInputStream(ByteArrayInputStream(crypto.decrypt(file.readBytes()))).run {
                if (readByte().toInt() != VERSION) return emptyList()
                List(readInt().also { require(it in 0..MAX_ENTRIES) }) { readAction() }
            }
        } catch (_: Exception) { file.delete(); emptyList() }
    }

    private fun save(list: List<PendingAction>) {
        if (list.isEmpty()) { file.delete(); return }
        file.parentFile?.mkdirs()
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply { writeByte(VERSION); writeInt(list.size); list.forEach { writeAction(it) }; flush() }
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(crypto.encrypt(out.toByteArray()))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun DataOutputStream.writeAction(a: PendingAction) = when (a) {
        is PendingAction.Send -> { writeByte(1); writeMail(a.mail); writeNullable(a.draftFolder); writeLong(a.draftUid ?: -1) }
        is PendingAction.Delete -> { writeByte(2); writeStr(a.folder); writeLong(a.uid) }
        is PendingAction.Seen -> { writeByte(3); writeStr(a.folder); writeLong(a.uid); writeBoolean(a.seen) }
    }

    private fun DataInputStream.readAction(): PendingAction = when (readByte().toInt()) {
        1 -> PendingAction.Send(readMail(), readNullable(), readLong().takeIf { it >= 0 })
        2 -> PendingAction.Delete(readStr(), readLong())
        3 -> PendingAction.Seen(readStr(), readLong(), readBoolean())
        else -> error("unbekannte Aktion")
    }

    private fun DataOutputStream.writeMail(m: OutgoingMail) {
        writeList(m.to); writeList(m.cc); writeList(m.bcc); writeStr(m.subject); writeStr(m.text)
        writeNullable(m.inReplyTo); writeNullable(m.references)
        writeInt(m.attachments.size)
        m.attachments.forEach { writeStr(it.fileName); writeStr(it.mimeType); writeInt(it.bytes.size); write(it.bytes) }
    }

    private fun DataInputStream.readMail(): OutgoingMail {
        val to = readList(); val cc = readList(); val bcc = readList(); val subject = readStr(); val text = readStr()
        val inReplyTo = readNullable(); val references = readNullable()
        val atts = List(readInt().also { require(it in 0..100) }) {
            val name = readStr(); val mime = readStr(); val n = readInt()
            require(n in 0..MAX_ATTACHMENT)
            OutgoingAttachment(name, mime, ByteArray(n).also { readFully(it) })
        }
        return OutgoingMail(to, cc, bcc, subject, text, atts, inReplyTo, references)
    }

    private fun DataOutputStream.writeStr(s: String) { val b = s.toByteArray(Charsets.UTF_8); writeInt(b.size); write(b) }
    private fun DataInputStream.readStr(): String {
        val n = readInt(); require(n in 0..MAX_STRING)
        return String(ByteArray(n).also { readFully(it) }, Charsets.UTF_8)
    }
    private fun DataOutputStream.writeNullable(s: String?) { writeBoolean(s != null); if (s != null) writeStr(s) }
    private fun DataInputStream.readNullable(): String? = if (readBoolean()) readStr() else null
    private fun DataOutputStream.writeList(l: List<String>) { writeInt(l.size); l.forEach { writeStr(it) } }
    private fun DataInputStream.readList(): List<String> = List(readInt().also { require(it in 0..10_000) }) { readStr() }

    private companion object {
        const val VERSION = 1
        const val MAX_ENTRIES = 10_000
        const val MAX_STRING = 16 * 1024 * 1024
        const val MAX_ATTACHMENT = 30 * 1024 * 1024
    }
}
