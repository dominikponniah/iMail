package ch.imail.core

import ch.imail.core.mail.Account
import ch.imail.core.mail.ConnectionSecurity
import ch.imail.core.mail.FolderRole
import ch.imail.core.mail.ImapClient
import ch.imail.core.mail.ImapIdleWatcher
import ch.imail.core.mail.MessageSummary
import ch.imail.core.mail.OutgoingAttachment
import ch.imail.core.mail.OutgoingMail
import ch.imail.core.mail.ServerConfig
import ch.imail.core.mail.SmtpClient
import ch.imail.core.mail.TlsOverrides
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Timeout
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Runs against an in-process GreenMail server with a self-signed certificate (simulated credentials only). */
class MailIntegrationTest {
    private lateinit var server: GreenMail
    private val account = Account(
        email = "alice@localhost", username = "alice",
        imap = ServerConfig("127.0.0.1", ServerSetupTest.IMAPS.port, ConnectionSecurity.SSL_TLS),
        smtp = ServerConfig("127.0.0.1", ServerSetupTest.SMTPS.port, ConnectionSecurity.SSL_TLS),
        displayName = "Alice",
    )
    private val password = "test-password"

    /** Trust-all context used ONLY here so the self-signed GreenMail certificate can be exercised. */
    private val testTls = TlsOverrides(
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) = Unit
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) = Unit
                override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            }), SecureRandom())
        }.socketFactory,
        checkServerIdentity = false,
    )

    @BeforeEach fun start() {
        server = GreenMail(arrayOf(ServerSetupTest.IMAPS, ServerSetupTest.SMTPS))
        server.setUser("alice@localhost", "alice", password)
        server.setUser("bob@localhost", "bob", password)
        server.start()
    }

    @AfterEach fun stop() = server.stop()

    private fun deliverToAlice(subject: String, body: String) {
        val msg = MimeMessage(Session.getInstance(Properties())).apply {
            setFrom("bob@localhost"); setRecipients(jakarta.mail.Message.RecipientType.TO, "alice@localhost")
            setSubject(subject); setText(body)
        }
        server.getUserManager().getUserByEmail("alice@localhost").deliver(msg)
    }

    @Test fun `self-signed certificate is rejected with default validation`() {
        assertFailsWith<Exception> { ImapClient(account, password).testConnection() }
        assertFailsWith<Exception> { SmtpClient(account, password).testConnection() }
    }

    @Test fun `wrong password is rejected`() {
        assertFailsWith<Exception> { ImapClientTestAccess.testConnection(account, "wrong", testTls) }
    }

    @Test fun `imap lists folders and messages and marks read`() {
        deliverToAlice("Hallo", "Erste Nachricht")
        val imap = ImapClientTestAccess.imap(account, password, testTls)
        assertTrue(imap.listFolders().any { it.role == FolderRole.INBOX })
        val list = imap.listMessages("INBOX")
        assertEquals(1, list.size)
        assertEquals("Hallo", list[0].subject)
        assertEquals("bob@localhost", list[0].from?.address)
        assertTrue(!list[0].seen)
        val body = imap.fetchMessage("INBOX", list[0].uid)
        assertTrue(body.text!!.contains("Erste Nachricht"))
        assertTrue(imap.listMessages("INBOX")[0].seen)
        assertEquals(1, imap.listMessages("INBOX", query = "Hallo").size)
        assertEquals(0, imap.listMessages("INBOX", query = "gibtesnicht").size)
    }

    @Test fun `smtp sends mail with attachment that imap can read back`() {
        val smtp = ImapClientTestAccess.smtp(account, password, testTls)
        val data = ByteArray(2048) { (it % 251).toByte() }
        smtp.send(OutgoingMail(to = listOf("bob@localhost"), subject = "Mit Anhang", text = "Siehe Anhang",
            attachments = listOf(OutgoingAttachment("../../report.pdf", "application/pdf", data))))
        server.waitForIncomingEmail(1)
        val bob = Account("bob@localhost", "bob", account.imap, account.smtp)
        val imap = ImapClientTestAccess.imap(bob, password, testTls)
        val m = imap.listMessages("INBOX").single()
        val body = imap.fetchMessage("INBOX", m.uid)
        assertEquals("report.pdf", body.attachments.single().fileName)
        assertContentEquals(data, imap.readAttachment("INBOX", m.uid, 0))
    }

    @Test fun `header injection in subject cannot add recipients`() {
        val smtp = ImapClientTestAccess.smtp(account, password, testTls)
        smtp.send(OutgoingMail(to = listOf("bob@localhost"), subject = "Hi\r\nBcc: alice@localhost", text = "x"))
        server.waitForIncomingEmail(1)
        assertEquals(1, server.receivedMessages.size)
        assertTrue(GreenMailUtil.getHeaders(server.receivedMessages[0]).lines().none { it.startsWith("Bcc:", true) })
    }

    @Test @Timeout(30) fun `idle watcher reports a newly arrived message`() {
        val latch = CountDownLatch(1)
        var got: List<MessageSummary> = emptyList()
        val ready = CountDownLatch(1)
        val watcher = ImapClientTestAccess.watcher(account, password, testTls, onNew = { got = it; latch.countDown() }, onReady = { ready.countDown() })
        val t = Thread { watcher.run() }.apply { isDaemon = true; start() }
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS), "watcher connected")
            Thread.sleep(500)
            deliverToAlice("Neu per IDLE", "push")
            assertTrue(latch.await(15, TimeUnit.SECONDS), "IDLE notification arrived")
            assertEquals("Neu per IDLE", got.single().subject)
        } finally { watcher.stop(); t.join(5000) }
    }
}
