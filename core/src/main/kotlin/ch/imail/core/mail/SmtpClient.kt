package ch.imail.core.mail

import ch.imail.core.security.FileNameSanitizer
import ch.imail.core.security.HeaderSanitizer
import jakarta.activation.DataHandler
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import java.util.Date

class SmtpClient internal constructor(
    private val account: Account,
    private val secret: SecretProvider,
    private val overrides: TlsOverrides?,
) {
    constructor(account: Account, password: String) : this(account, SecretProvider { password }, null)
    constructor(account: Account, secret: SecretProvider) : this(account, secret, null)
    internal constructor(account: Account, password: String, overrides: TlsOverrides?) : this(account, SecretProvider { password }, overrides)

    private val session: Session get() = MailSession.create("smtp", account.smtp, overrides, account.auth)

    fun testConnection() {
        session.getTransport("smtp").apply { connect(account.smtp.host, account.smtp.port, account.username, secret.get()); close() }
    }

    /** Validates every address, strips header-injection characters and sends. Returns the sent message for the "Sent" folder. */
    fun send(mail: OutgoingMail): MimeMessage {
        val msg = OutgoingMessageBuilder.build(session, account, mail)
        val transport = session.getTransport("smtp")
        try {
            transport.connect(account.smtp.host, account.smtp.port, account.username, secret.get())
            transport.sendMessage(msg, msg.allRecipients)
        } finally { runCatching { transport.close() } }
        return msg
    }
}

/** Builds a MIME message from user input; shared by sending and draft saving. */
internal object OutgoingMessageBuilder {
    fun build(session: Session, account: Account, mail: OutgoingMail, allowEmptyRecipients: Boolean = false): MimeMessage {
        require(allowEmptyRecipients || mail.to.isNotEmpty()) { "Kein Empfänger" }
        val msg = MimeMessage(session)
        val from = HeaderSanitizer.requireValidAddress(account.email)
        if (account.displayName.isNotBlank()) from.personal = HeaderSanitizer.cleanValue(account.displayName)
        msg.setFrom(from)
        fun set(type: Message.RecipientType, list: List<String>) {
            if (list.isNotEmpty()) msg.setRecipients(type, list.map { HeaderSanitizer.requireValidAddress(it) as InternetAddress }.toTypedArray())
        }
        set(Message.RecipientType.TO, mail.to)
        set(Message.RecipientType.CC, mail.cc)
        set(Message.RecipientType.BCC, mail.bcc)
        msg.setSubject(HeaderSanitizer.cleanValue(mail.subject), "UTF-8")
        msg.sentDate = Date()
        mail.inReplyTo?.let { msg.setHeader("In-Reply-To", HeaderSanitizer.cleanValue(it)) }
        mail.references?.let { msg.setHeader("References", HeaderSanitizer.cleanValue(it)) }
        if (mail.attachments.isEmpty()) {
            msg.setText(mail.text, "UTF-8")
        } else {
            val multi = MimeMultipart("mixed")
            multi.addBodyPart(MimeBodyPart().apply { setText(mail.text, "UTF-8") })
            for (a in mail.attachments) multi.addBodyPart(MimeBodyPart().apply {
                dataHandler = DataHandler(ByteArrayDataSource(a.bytes, a.mimeType.substringBefore('\n').substringBefore('\r')))
                fileName = FileNameSanitizer.sanitize(a.fileName)
                disposition = "attachment"
            })
            msg.setContent(multi)
        }
        msg.saveChanges()
        return msg
    }
}
