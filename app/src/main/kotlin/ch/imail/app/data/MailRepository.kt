package ch.imail.app.data

import ch.imail.core.mail.Account
import ch.imail.core.mail.FolderRole
import ch.imail.core.mail.ImapClient
import ch.imail.core.mail.MailFolder
import ch.imail.core.mail.MessageBody
import ch.imail.core.mail.MessageSummary
import ch.imail.core.mail.OutgoingMail
import ch.imail.core.mail.SmtpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Suspending facade over the blocking core clients. Mail content is kept in memory only, never written to disk. */
class MailRepository(private val login: StoredLogin) {
    val account: Account get() = login.account
    private val imap = ImapClient(login.account, login.password)
    private val smtp = SmtpClient(login.account, login.password)

    suspend fun folders(): List<MailFolder> = io { imap.listFolders() }
    suspend fun messages(folder: String, query: String? = null): List<MessageSummary> = io { imap.listMessages(folder, 50, query) }
    suspend fun message(folder: String, uid: Long): MessageBody = io { imap.fetchMessage(folder, uid) }
    suspend fun attachment(folder: String, uid: Long, index: Int): ByteArray = io { imap.readAttachment(folder, uid, index) }
    suspend fun setSeen(folder: String, uid: Long, seen: Boolean) = io { imap.setSeen(folder, uid, seen) }
    suspend fun delete(folder: String, uid: Long) = io { imap.delete(folder, uid) }

    suspend fun send(mail: OutgoingMail) = io {
        val sent = smtp.send(mail)
        runCatching { imap.append(FolderRole.SENT, sent) }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
