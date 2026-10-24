package ch.imail.core

import ch.imail.core.mail.Account
import ch.imail.core.mail.ImapClient
import ch.imail.core.mail.ImapIdleWatcher
import ch.imail.core.mail.MessageSummary
import ch.imail.core.mail.SmtpClient
import ch.imail.core.mail.TlsOverrides

/** Test-only access to the internal TLS override constructors. */
internal object ImapClientTestAccess {
    fun imap(a: Account, pw: String, tls: TlsOverrides) = ImapClient(a, pw, tls)
    fun smtp(a: Account, pw: String, tls: TlsOverrides) = SmtpClient(a, pw, tls)
    fun testConnection(a: Account, pw: String, tls: TlsOverrides) = imap(a, pw, tls).testConnection()
    fun watcher(a: Account, pw: String, tls: TlsOverrides, onNew: (List<MessageSummary>) -> Unit, onReady: () -> Unit) =
        ImapIdleWatcher(imap(a, pw, tls), "INBOX", onNew, {}, onReady)
}
