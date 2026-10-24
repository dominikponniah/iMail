package ch.imail.core

import ch.imail.core.cache.AesGcmCrypto
import ch.imail.core.cache.MailCache
import ch.imail.core.mail.Account
import ch.imail.core.mail.AuthType
import ch.imail.core.mail.ConnectionSecurity
import ch.imail.core.mail.FolderRole
import ch.imail.core.mail.MailService
import ch.imail.core.mail.MessageSummary
import ch.imail.core.mail.OutgoingMail
import ch.imail.core.mail.SecretProvider
import ch.imail.core.mail.ServerConfig
import ch.imail.core.mail.TlsOverrides
import ch.imail.core.oauth.OAuthProviders
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
import javax.crypto.SecretKey
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

private fun summary(uid: Long, seen: Boolean = false) = MessageSummary(uid, ch.imail.core.mail.MailAddress("Bob", "bob@firma.ch"), "Betreff $uid äöü €", "Vorschau", 1_700_000_000_000, seen, true)

class MailCacheTest {
    @TempDir lateinit var dir: File

    @Test fun `headers bodies and attachments round trip and are encrypted on disk`() {
        val cache = MailCache(dir, AesGcmCrypto(newKey()))
        cache.saveMessages("INBOX", listOf(summary(1), summary(2, seen = true)))
        assertEquals(listOf(1L, 2L), cache.messages("INBOX")!!.map { it.uid })
        assertEquals("Betreff 1 äöü €", cache.messages("INBOX")!![0].subject)
        cache.saveAttachment("INBOX", 1, 0, "geheim-pdf".toByteArray())
        assertEquals("geheim-pdf", String(cache.attachment("INBOX", 1, 0)!!))
        cache.setSeen("INBOX", 1, true)
        assertTrue(cache.messages("INBOX")!![0].seen)
        // no plaintext on disk: neither subject, folder name nor attachment content
        val all = dir.listFiles()!!.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) + it.name }
        listOf("Betreff", "INBOX", "geheim", "bob@firma.ch").forEach { assertTrue(!all.contains(it), it) }
    }

    @Test fun `wrong key or tampered file is a cache miss and is deleted`() {
        MailCache(dir, AesGcmCrypto(newKey())).saveMessages("INBOX", listOf(summary(1)))
        assertNull(MailCache(dir, AesGcmCrypto(newKey())).messages("INBOX"))
        assertEquals(0, dir.listFiles()!!.count { it.name.startsWith("h_") })

        val c = MailCache(dir, AesGcmCrypto(newKey()))
        c.saveMessages("INBOX", listOf(summary(1)))
        val f = dir.listFiles()!!.first { it.name.startsWith("h_") }
        f.writeBytes(f.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
        assertNull(c.messages("INBOX"))
    }

    @Test fun `old entries are trimmed`() {
        val cache = MailCache(dir, AesGcmCrypto(newKey()), maxHeadersPerFolder = 3)
        cache.saveMessages("INBOX", (1L..10L).map { summary(it) })
        assertEquals(3, cache.messages("INBOX")!!.size)
    }
}

class AccountsAndOAuthTest {
    @Test fun `oauth accounts only offer XOAUTH2`() {
        val cfg = ServerConfig("imap.example.com", 993, ConnectionSecurity.SSL_TLS)
        val oauth = ch.imail.core.mail.MailSessionAccess.session("imap", cfg, AuthType.OAUTH2)
        assertEquals("XOAUTH2", oauth.getProperty("mail.imap.auth.mechanisms"))
        assertEquals("true", oauth.getProperty("mail.imap.ssl.checkserveridentity"))
        assertNull(ch.imail.core.mail.MailSessionAccess.session("imap", cfg, AuthType.PASSWORD).getProperty("mail.imap.auth.mechanisms"))
    }

    @Test fun `email is read from id token claims`() {
        fun jwt(json: String) = "x." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray()) + ".y"
        assertEquals("a@gmail.com", OAuthProviders.emailFromIdToken(jwt("""{"sub":"1","email":"a@gmail.com"}""")))
        assertEquals("b@outlook.com", OAuthProviders.emailFromIdToken(jwt("""{"preferred_username":"b@outlook.com"}""")))
        assertNull(OAuthProviders.emailFromIdToken("garbage"))
        assertNull(OAuthProviders.emailFromIdToken(jwt("""{"email":"no-at-sign"}""")))
    }

    @Test fun `providers use tls endpoints only`() {
        OAuthProviders.all.forEach { p ->
            assertTrue(p.authorizationEndpoint.startsWith("https://") && p.tokenEndpoint.startsWith("https://"), p.id)
            assertTrue(p.imap.security == ConnectionSecurity.SSL_TLS)
        }
        assertNotNull(OAuthProviders.byId("google")); assertNull(OAuthProviders.byId("evil"))
    }

    @Test fun `accounts get unique ids`() {
        val s = ServerConfig("h", 993, ConnectionSecurity.SSL_TLS)
        assertTrue(Account("a@b.ch", "a", s, s).id != Account("a@b.ch", "a", s, s).id)
    }
}

class MailServiceOfflineTest {
    @TempDir lateinit var dir: File
    private lateinit var server: GreenMail
    private val account = Account(
        "alice@localhost", "alice",
        ServerConfig("127.0.0.1", ServerSetupTest.IMAPS.port, ConnectionSecurity.SSL_TLS),
        ServerConfig("127.0.0.1", ServerSetupTest.SMTPS.port, ConnectionSecurity.SSL_TLS),
        "Alice",
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

    @BeforeEach fun start() {
        server = GreenMail(arrayOf(ServerSetupTest.IMAPS, ServerSetupTest.SMTPS)).also {
            it.setUser("alice@localhost", "alice", "pw"); it.setUser("bob@localhost", "bob", "pw"); it.start()
        }
    }
    @AfterEach fun stop() { runCatching { server.stop() } }

    private fun service() = MailService(account, SecretProvider { "pw" }, MailCache(dir, AesGcmCrypto(newKey()).also { crypto = it }), trustAll)
    private var crypto: AesGcmCrypto? = null

    private fun deliver(subject: String) {
        val msg = MimeMessage(Session.getInstance(Properties())).apply {
            setFrom("bob@localhost"); setRecipients(jakarta.mail.Message.RecipientType.TO, "alice@localhost"); setSubject(subject); setText("Inhalt von $subject")
        }
        server.userManager.getUserByEmail("alice@localhost").deliver(msg)
    }

    @Test fun `mail stays readable offline and reconnect errors are not masked for new data`() {
        deliver("Offline-Test")
        val svc = service()
        val online = svc.messages("INBOX")
        assertTrue(!online.offline)
        svc.folders()
        val uid = online.value.single().uid
        assertTrue(svc.message("INBOX", uid).value.text!!.contains("Inhalt von Offline-Test"))

        server.stop() // server unreachable
        val off = svc.messages("INBOX")
        assertTrue(off.offline)
        assertEquals("Offline-Test", off.value.single().subject)
        assertTrue(svc.folders().offline)
        assertTrue(svc.message("INBOX", uid).value.text!!.contains("Inhalt von Offline-Test"))
        assertEquals(1, svc.messages("INBOX", "offline").value.size)
        assertEquals(0, svc.messages("INBOX", "gibtesnicht").value.size)
        // a folder never cached cannot be shown offline
        assertFailsWith<Exception> { svc.messages("Nie-Gesehen") }
    }

    @Test fun `wrong password is reported even if a cache exists`() {
        deliver("x")
        val cache = MailCache(dir, AesGcmCrypto(newKey()))
        MailService(account, SecretProvider { "pw" }, cache, trustAll).messages("INBOX")
        assertFailsWith<jakarta.mail.AuthenticationFailedException> { MailService(account, SecretProvider { "falsch" }, cache, trustAll).messages("INBOX") }
    }

    @Test fun `prefetch caches unread bodies without marking them read`() {
        deliver("Prefetch")
        val svc = service()
        val list = svc.messages("INBOX").value
        svc.prefetch("INBOX", list)
        assertTrue(!svc.messages("INBOX").value.single().seen)
        server.stop()
        assertTrue(svc.message("INBOX", list.single().uid).value.text!!.contains("Prefetch"))
    }

    @Test fun `drafts are saved on the server, listed, replaced and removed when sent`() {
        val svc = service()
        val first = svc.saveDraft(OutgoingMail(to = emptyList(), subject = "Entwurf 1", text = "halb fertig"))
        assertNotNull(first)
        val folders = svc.folders().value
        val drafts = folders.first { it.role == FolderRole.DRAFTS }
        assertEquals(listOf("Entwurf 1"), svc.messages(drafts.fullName).value.map { it.subject })

        val second = svc.saveDraft(OutgoingMail(to = listOf("bob@localhost"), subject = "Entwurf 2", text = "fertig"), replaceUid = first)
        assertNotNull(second)
        assertEquals(listOf("Entwurf 2"), svc.messages(drafts.fullName).value.map { it.subject })
        assertEquals("bob@localhost", svc.message(drafts.fullName, second!!).value.to.single().address)

        svc.send(OutgoingMail(listOf("bob@localhost"), subject = "Entwurf 2", text = "fertig"), drafts.fullName to second)
        server.waitForIncomingEmail(1)
        assertEquals(0, svc.messages(drafts.fullName).value.size)
    }
}
