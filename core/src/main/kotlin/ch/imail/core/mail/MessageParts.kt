package ch.imail.core.mail

import ch.imail.core.security.FileNameSanitizer
import jakarta.mail.BodyPart
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.MimeUtility

/** Walks a MIME tree and collects body text, HTML and attachments. */
internal class MessageParts private constructor() {
    var text: String? = null
    var html: String? = null
    val attachmentParts = mutableListOf<Part>()
    val attachments = mutableListOf<AttachmentInfo>()

    private fun walk(part: Part, depth: Int) {
        if (depth > 12) return
        val disposition = part.disposition
        val isAttachment = Part.ATTACHMENT.equals(disposition, true) || (part.fileName != null && !part.isMimeType("text/plain") && !part.isMimeType("text/html"))
        when {
            part.isMimeType("multipart/*") -> {
                val mp = part.content as Multipart
                for (i in 0 until mp.count) walk(mp.getBodyPart(i), depth + 1)
            }
            part.isMimeType("message/rfc822") && !isAttachment -> Unit
            (!isAttachment && part.isMimeType("text/plain")) -> if (text == null) text = part.content as? String
            (!isAttachment && part.isMimeType("text/html")) -> if (html == null) html = part.content as? String
            else -> addAttachment(part)
        }
    }

    private fun addAttachment(part: Part) {
        val cid = (part as? BodyPart)?.getHeader("Content-ID")?.firstOrNull()?.trim('<', '>', ' ')
        val raw = part.fileName?.let { runCatching { MimeUtility.decodeText(it) }.getOrDefault(it) }
        val mime = part.contentType.substringBefore(';').trim().lowercase()
        attachments += AttachmentInfo(
            index = attachmentParts.size,
            fileName = FileNameSanitizer.sanitize(raw),
            mimeType = mime,
            size = part.size.coerceAtLeast(0),
            contentId = cid,
            inline = Part.INLINE.equals(part.disposition, true) || cid != null,
        )
        attachmentParts += part
    }

    companion object {
        fun parse(root: Part): MessageParts = MessageParts().also { it.walk(root, 0) }
    }
}
