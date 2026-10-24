package ch.imail.core.cache

import ch.imail.core.mail.AttachmentInfo
import ch.imail.core.mail.FolderRole
import ch.imail.core.mail.MailAddress
import ch.imail.core.mail.MailFolder
import ch.imail.core.mail.MessageBody
import ch.imail.core.mail.MessageSummary
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Encrypted on-disk copy of one account's mail for offline reading: folder list, message headers, bodies and small attachments.
 * Every file is AES-GCM encrypted, written atomically, and named by a hash so folder names and UIDs do not leak.
 * Anything that fails to decrypt or parse is treated as a cache miss and deleted.
 */
class MailCache(
    private val dir: File,
    private val crypto: CacheCrypto,
    private val maxHeadersPerFolder: Int = 200,
    private val maxBodies: Int = 300,
    private val maxAttachmentBytes: Int = 2 * 1024 * 1024,
    private val maxBodyChars: Int = 2_000_000,
) {
    init { dir.mkdirs() }

    // --- folders ---------------------------------------------------------------------------------------------------
    fun saveFolders(folders: List<MailFolder>) = write("folders", encode {
        writeInt(folders.size)
        folders.forEach { writeStr(it.fullName); writeStr(it.displayName); writeInt(it.role.ordinal); writeInt(it.unread) }
    })

    fun folders(): List<MailFolder>? = read("folders") {
        List(readInt()) { MailFolder(readStr(), readStr(), FolderRole.entries[readInt()], readInt()) }
    }

    // --- headers ---------------------------------------------------------------------------------------------------
    fun saveMessages(folder: String, list: List<MessageSummary>) = write(fileOf("h", folder), encode {
        val kept = list.take(maxHeadersPerFolder)
        writeInt(kept.size)
        kept.forEach { writeSummary(it) }
    })

    fun messages(folder: String): List<MessageSummary>? = read(fileOf("h", folder)) { List(readInt()) { readSummary() } }

    fun setSeen(folder: String, uid: Long, seen: Boolean) {
        messages(folder)?.let { l -> saveMessages(folder, l.map { if (it.uid == uid) it.copy(seen = seen) else it }) }
        body(folder, uid)?.let { saveBody(folder, it.copy(summary = it.summary.copy(seen = seen))) }
    }

    fun remove(folder: String, uid: Long) {
        messages(folder)?.let { l -> saveMessages(folder, l.filterNot { it.uid == uid }) }
        File(dir, fileOf("b", folder, uid)).delete()
    }

    // --- bodies and attachments --------------------------------------------------------------------------------------
    fun saveBody(folder: String, body: MessageBody) {
        if ((body.html?.length ?: 0) + (body.text?.length ?: 0) > maxBodyChars) return
        write(fileOf("b", folder, body.summary.uid), encode {
            writeSummary(body.summary)
            writeList(body.to) { writeAddr(it) }; writeList(body.cc) { writeAddr(it) }
            writeNullable(body.messageId); writeNullable(body.references); writeNullable(body.html); writeNullable(body.text)
            writeList(body.attachments) { writeStr(it.fileName); writeStr(it.mimeType); writeInt(it.size); writeInt(it.index); writeNullable(it.contentId); writeBoolean(it.inline) }
        })
        trim("b_", maxBodies)
    }

    fun body(folder: String, uid: Long): MessageBody? = read(fileOf("b", folder, uid)) {
        val summary = readSummary()
        val to = readList { readAddr() }; val cc = readList { readAddr() }
        val messageId = readNullable(); val references = readNullable(); val html = readNullable(); val text = readNullable()
        val atts = readList { AttachmentInfo(fileName = readStr(), mimeType = readStr(), size = readInt(), index = readInt(), contentId = readNullable(), inline = readBoolean()) }
        MessageBody(summary, to, cc, messageId, references, html, text, atts)
    }

    fun saveAttachment(folder: String, uid: Long, index: Int, bytes: ByteArray) {
        if (bytes.size > maxAttachmentBytes) return
        write(fileOf("a", folder, uid, index), bytes)
        trim("a_", 100)
    }

    fun attachment(folder: String, uid: Long, index: Int): ByteArray? = readRaw(fileOf("a", folder, uid, index))

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    // --- plumbing ----------------------------------------------------------------------------------------------------
    private fun fileOf(kind: String, folder: String, vararg ids: Number): String =
        kind + "_" + hash(folder) + ids.joinToString("") { "_$it" }

    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    private fun write(name: String, plain: ByteArray) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(crypto.encrypt(plain))
        if (!tmp.renameTo(File(dir, name))) { File(dir, name).delete(); tmp.renameTo(File(dir, name)) }
    }

    private fun readRaw(name: String): ByteArray? {
        val f = File(dir, name).takeIf { it.isFile } ?: return null
        return try { crypto.decrypt(f.readBytes()) } catch (_: Exception) { f.delete(); null }
    }

    private fun <T> read(name: String, parse: DataInputStream.() -> T): T? {
        val raw = readRaw(name) ?: return null
        return try { DataInputStream(ByteArrayInputStream(raw)).run { if (readByte().toInt() != VERSION) null else parse() } }
        catch (_: Exception) { File(dir, name).delete(); null }
    }

    private fun trim(prefix: String, max: Int) {
        val files = dir.listFiles { f -> f.name.startsWith(prefix) && !f.name.endsWith(".tmp") }.orEmpty()
        if (files.size > max) files.sortedBy { it.lastModified() }.take(files.size - max).forEach { it.delete() }
    }

    private fun encode(block: DataOutputStream.() -> Unit): ByteArray =
        ByteArrayOutputStream().also { DataOutputStream(it).apply { writeByte(VERSION); block(); flush() } }.toByteArray()

    private fun DataOutputStream.writeStr(s: String) { val b = s.toByteArray(Charsets.UTF_8); writeInt(b.size); write(b) }
    private fun DataInputStream.readStr(): String {
        val n = readInt()
        require(n in 0..MAX_STRING) { "ungültige Länge" }
        return String(ByteArray(n).also { readFully(it) }, Charsets.UTF_8)
    }
    private fun DataOutputStream.writeNullable(s: String?) { writeBoolean(s != null); if (s != null) writeStr(s) }
    private fun DataInputStream.readNullable(): String? = if (readBoolean()) readStr() else null
    private fun <T> DataOutputStream.writeList(l: List<T>, each: DataOutputStream.(T) -> Unit) { writeInt(l.size); l.forEach { each(it) } }
    private fun <T> DataInputStream.readList(each: DataInputStream.() -> T): List<T> {
        val n = readInt(); require(n in 0..MAX_LIST) { "ungültige Liste" }
        return List(n) { each() }
    }
    private fun DataOutputStream.writeAddr(a: MailAddress) { writeNullable(a.name); writeStr(a.address) }
    private fun DataInputStream.readAddr() = MailAddress(readNullable(), readStr())
    private fun DataOutputStream.writeSummary(m: MessageSummary) {
        writeLong(m.uid); writeBoolean(m.from != null); m.from?.let { writeAddr(it) }
        writeStr(m.subject); writeStr(m.preview); writeLong(m.dateMs); writeBoolean(m.seen); writeBoolean(m.hasAttachments)
    }
    private fun DataInputStream.readSummary() = MessageSummary(
        uid = readLong(), from = if (readBoolean()) readAddr() else null, subject = readStr(), preview = readStr(),
        dateMs = readLong(), seen = readBoolean(), hasAttachments = readBoolean(),
    )

    private companion object {
        const val VERSION = 1
        const val MAX_STRING = 16 * 1024 * 1024
        const val MAX_LIST = 100_000
    }
}
