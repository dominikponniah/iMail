package ch.imail.core.mail

enum class ConnectionSecurity { SSL_TLS, STARTTLS }

data class ServerConfig(val host: String, val port: Int, val security: ConnectionSecurity)

data class Account(
    val email: String,
    val username: String,
    val imap: ServerConfig,
    val smtp: ServerConfig,
    val displayName: String = "",
)

enum class FolderRole { INBOX, SENT, DRAFTS, TRASH, ARCHIVE, JUNK, OTHER }

data class MailFolder(val fullName: String, val displayName: String, val role: FolderRole, val unread: Int)

data class MailAddress(val name: String?, val address: String) {
    val display: String get() = name?.takeIf { it.isNotBlank() } ?: address
}

data class MessageSummary(
    val uid: Long,
    val from: MailAddress?,
    val subject: String,
    val preview: String,
    val dateMs: Long,
    val seen: Boolean,
    val hasAttachments: Boolean,
)

data class AttachmentInfo(val index: Int, val fileName: String, val mimeType: String, val size: Int, val contentId: String?, val inline: Boolean)

data class MessageBody(
    val summary: MessageSummary,
    val to: List<MailAddress>,
    val cc: List<MailAddress>,
    val messageId: String?,
    val references: String?,
    val html: String?,
    val text: String?,
    val attachments: List<AttachmentInfo>,
)

class OutgoingAttachment(val fileName: String, val mimeType: String, val bytes: ByteArray)

data class OutgoingMail(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String,
    val text: String,
    val attachments: List<OutgoingAttachment> = emptyList(),
    val inReplyTo: String? = null,
    val references: String? = null,
)
