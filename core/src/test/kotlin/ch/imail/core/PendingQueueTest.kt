package ch.imail.core

import ch.imail.core.cache.AesGcmCrypto
import ch.imail.core.cache.MailCache
import ch.imail.core.cache.PendingAction
import ch.imail.core.cache.PendingQueue
import ch.imail.core.mail.Account
import ch.imail.core.mail.ConnectionSecurity
import ch.imail.core.mail.MailService
import ch.imail.core.mail.Outcome
import ch.imail.core.mail.OutgoingAttachment
import ch.imail.core.mail.OutgoingMail
import ch.imail.core.mail.SecretProvider
import ch.imail.core.mail.ServerConfig
import ch.imail.core.mail.TlsOverrides
import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetupTest
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Properties
import javax.crypto.KeyGenerator
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PendingQueueTest {
    @TempDir lateinit var dir: File
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun `actions persist in order, are encrypted and survive a restart`() {
        val file = File(dir, "q")
        val q = PendingQueue(file, AesGcmCrypto(key))
        q.add(PendingAction.Send(OutgoingMail(listOf("bob@x.ch"), subject = "Geheimer Betreff", text = "Text", attachments = listOf(OutgoingAttachment("a.pdf", "application/pdf", byteArrayOf(1, 2, 3)))), "Drafts", 7))
        q.add(PendingAction.Delete("INBOX", 5))
        q.add(PendingAction.Seen("INBOX", 6, true))
        assertTrue(!String(file.readBytes(), Charsets.ISO_8859_1).contains("Geheimer"))

        val again = PendingQueue(file, AesGcmCrypto(key)).all()
        assertEquals(3, again.size)
        val send = again[0] as PendingAction.Send
        assertEquals("Geheimer Betreff", send.mail.subject); assertEquals(7L, send.draftUid)
        assertEquals(listOf<Byte>(1, 2, 3), send.mail.attachments.single().bytes.toList())
        assertEquals(PendingAction.Delete("INBOX", 5), again[1])

        PendingQueue(file, AesGcmCrypto(key)).apply { removeFirst(); removeFirst(); removeFirst() }
        assertTrue(!file.exists())
    }

    @Test fun `corrupt queue file is discarded`() {
        val file = File(dir, "q").apply { writeBytes(ByteArray(40) { 7 }) }
        assertEquals(0, PendingQueue(file, AesGcmCrypto(key)).size())
        assertTrue(!file.exists())
    }
}

class OfflineActionsTest {
    @TempDir lateinit var dir: File
    private var server: GreenMail? = null
    private val account = Account(
        "alice@localhost", "alice",
        ServerConfig("127.0.0.1", ServerSetupTest.IMAPS.port, ConnectionSecurity.SSL_TLS),
        ServerConfig("127.0.0.1", ServerSetupTest.SMTPS.port, ConnectionSecurity.SSL_TLS), "Alice",
    )
    private val trustAll = TlsOverrides(
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) = Unit
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) = Unit
                override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            }), SecureRandom())
        }.socketFactory, checkServerIdentity = false,
    )

    private fun startServer() {
        server = GreenMail(arrayOf(ServerSetupTest.IMAPS, ServerSetupTest.SMTPS)).also {
            it.setUser("alice@localhost", "alice", "pw"); it.setUser("bob@localhost", "bob", "pw"); it.start()
        }
    }

    @BeforeEach fun start() = startServer()
    @AfterEach fun stop() { runCatching { server?.stop() } }

    private fun deliver(subject: String) {
        val msg = MimeMessage(Session.getInstance(Properties())).apply {
            setFrom("bob@localhost"); setRecipients(jakarta.mail.Message.RecipientType.TO, "alice@localhost"); setSubject(subject); setText("Inhalt")
        }
        server!!.userManager.getUserByEmail("alice@localhost").deliver(msg)
    }

    private fun service(): MailService {
        val crypto = AesGcmCrypto(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        return MailService(account, SecretProvider { "pw" }, MailCache(File(dir, "c"), crypto), PendingQueue(File(dir, "q"), crypto), trustAll)
    }

    @Test fun `delete, read flag and send made offline are queued and applied after reconnecting`() {
        deliver("Zu löschen"); deliver("Bleibt")
        val svc = service()
        val list = svc.messages("INBOX").value
        val toDelete = list.first { it.subject == "Zu löschen" }
        val toRead = list.first { it.subject == "Bleibt" }

        server!!.stop() // offline from here on
        assertEquals(Outcome.QUEUED, svc.delete("INBOX", toDelete.uid))
        assertEquals(Outcome.QUEUED, svc.markSeen("INBOX", toRead.uid, true))
        assertEquals(Outcome.QUEUED, svc.send(OutgoingMail(listOf("bob@localhost"), subject = "Vorgemerkt", text = "später")))
        assertEquals(3, svc.pendingCount())
        // local view reflects the changes immediately
        val local = svc.messages("INBOX")
        assertTrue(local.offline)
        assertEquals(listOf("Bleibt"), local.value.map { it.subject })
        assertTrue(local.value.single().seen)
        // still offline: flushing keeps everything queued, in order
        assertEquals(0, svc.flush().applied); assertEquals(3, svc.pendingCount())

        startServer() // network is back (fresh server state with the same two mails)
        deliver("Zu löschen"); deliver("Bleibt")
        val result = svc.flush()
        assertEquals(3, result.applied); assertEquals(0, result.remaining)
        server!!.waitForIncomingEmail(1)
        assertTrue(server!!.receivedMessages.any { it.subject == "Vorgemerkt" })
        val after = svc.messages("INBOX").value
        assertEquals(listOf("Bleibt"), after.map { it.subject })
        assertTrue(after.single().seen)
    }

    @Test fun `invalid mail is rejected immediately and never queued`() {
        val svc = service()
        server!!.stop()
        assertFailsWith<IllegalArgumentException> { svc.send(OutgoingMail(listOf("kein-at-zeichen"), subject = "x", text = "y")) }
        assertFailsWith<IllegalArgumentException> { svc.send(OutgoingMail(emptyList(), subject = "x", text = "y")) }
        assertEquals(0, svc.pendingCount())
    }

    @Test fun `bad password and certificate problems are not queued`() {
        val crypto = AesGcmCrypto(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        val noTrust = MailService(account, SecretProvider { "pw" }, null, PendingQueue(File(dir, "q2"), crypto))
        assertFailsWith<Exception> { noTrust.markSeen("INBOX", 1, true) } // self-signed cert -> SSL error, must surface
        assertEquals(0, noTrust.pendingCount())
    }
}
