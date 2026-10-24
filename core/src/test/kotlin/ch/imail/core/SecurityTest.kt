package ch.imail.core

import ch.imail.core.logo.FreemailDomains
import ch.imail.core.logo.HttpResponseParser
import ch.imail.core.logo.ImageSniffer
import ch.imail.core.logo.ResponseTooLargeException
import ch.imail.core.logo.UnsafeTargetException
import ch.imail.core.logo.UrlGuard
import ch.imail.core.security.FileNameSanitizer
import ch.imail.core.security.HeaderSanitizer
import ch.imail.core.security.HtmlSanitizer
import ch.imail.core.security.LinkPolicy
import java.io.ByteArrayInputStream
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FreemailDomainsTest {
    @Test fun `common freemail providers are detected`() {
        listOf("gmail.com", "GMX.ch", "gmx.at", "outlook.com", "icloud.com", "bluewin.ch", "hotmail.co.uk", "yahoo.fr", "web.de", "proton.me")
            .forEach { assertTrue(FreemailDomains.isFreemail(it), it) }
    }

    @Test fun `company domains are not freemail`() {
        listOf("swisscom.ch", "apple.com", "mail.firma.ch").forEach { assertFalse(FreemailDomains.isFreemail(it), it) }
    }

    @Test fun `domain extraction normalises and rejects junk`() {
        assertEquals("firma.ch", FreemailDomains.domainOf("Max <max@Firma.CH>"))
        assertNull(FreemailDomains.domainOf("max@localhost"))
        assertNull(FreemailDomains.domainOf("max@[127.0.0.1]"))
        assertNull(FreemailDomains.domainOf("no-at-sign"))
        assertNull(FreemailDomains.domainOf("a@-bad-.com"))
    }

    @Test fun `lookup candidates fall back to the parent domain`() {
        assertEquals(listOf("mail.firma.ch", "firma.ch"), FreemailDomains.lookupCandidates("mail.firma.ch"))
        assertEquals(listOf("mail.firma.co.uk", "firma.co.uk"), FreemailDomains.lookupCandidates("mail.firma.co.uk"))
        assertEquals(listOf("firma.ch"), FreemailDomains.lookupCandidates("firma.ch"))
    }
}

class UrlGuardTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test fun `private and special addresses are blocked`() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.5", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0",
            "224.0.0.1", "::1", "fe80::1", "fc00::1", "fd12:3456::1", "::ffff:10.0.0.1")
            .forEach { assertFalse(UrlGuard.isPublic(ip(it)), it) }
    }

    @Test fun `public addresses pass`() {
        listOf("8.8.8.8", "93.184.216.34", "2606:4700:4700::1111").forEach { assertTrue(UrlGuard.isPublic(ip(it)), it) }
    }

    @Test fun `hosts resolving to private ranges are rejected even if one answer is public`() {
        val mixed = { _: String -> listOf(ip("8.8.8.8"), ip("10.0.0.1")) }
        assertFailsWith<UnsafeTargetException> { UrlGuard.resolvePublic("evil.example.com", mixed) }
    }

    @Test fun `ip literals and internal names are rejected before DNS`() {
        val neverCalled = { _: String -> error("resolver must not be called") }
        listOf("127.0.0.1", "localhost", "intranet.local", "printer.internal", "nodots", "[::1]", "::1")
            .forEach { assertFailsWith<UnsafeTargetException>(it) { UrlGuard.resolvePublic(it, neverCalled) } }
    }

    @Test fun `public host returns the validated address`() {
        assertEquals(ip("93.184.216.34"), UrlGuard.resolvePublic("example.com") { listOf(ip("93.184.216.34")) })
    }
}

class HttpResponseParserTest {
    private fun resp(headers: String, body: ByteArray) = ByteArrayInputStream(("HTTP/1.0 200 OK\r\n$headers\r\n").toByteArray() + body)

    @Test fun `parses status headers and body`() {
        val r = HttpResponseParser.parse(resp("Content-Type: image/png\r\n", byteArrayOf(1, 2, 3)), 100)
        assertEquals(200, r.status)
        assertEquals("image/png", r.headers["content-type"])
        assertEquals(3, r.body.size)
    }

    @Test fun `body larger than limit is rejected`() {
        assertFailsWith<ResponseTooLargeException> { HttpResponseParser.parse(resp("", ByteArray(500)), 100) }
    }

    @Test fun `declared content length above limit is rejected before reading`() {
        assertFailsWith<ResponseTooLargeException> { HttpResponseParser.parse(resp("Content-Length: 999999\r\n", ByteArray(1)), 100) }
    }
}

class ImageSnifferTest {
    @Test fun `accepts raster formats only`() {
        assertEquals("image/png", ImageSniffer.sniff(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)))
        assertEquals("image/jpeg", ImageSniffer.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertNull(ImageSniffer.sniff("<svg onload=alert(1)></svg>".toByteArray()))
        assertNull(ImageSniffer.sniff("<html>".toByteArray()))
    }
}

class HtmlSanitizerTest {
    @Test fun `scripts handlers iframes and forms are removed`() {
        val out = HtmlSanitizer.sanitize(
            """<p onclick="x()">Hi</p><script>alert(1)</script><iframe src="https://a.b"></iframe><form action="https://evil"><input></form><style>p{}</style>""",
            allowRemoteImages = false,
        ).html
        listOf("script", "onclick", "iframe", "<form", "<input", "<style").forEach { assertFalse(out.contains(it, true), it) }
        assertTrue(out.contains("Hi"))
    }

    @Test fun `javascript and data links lose their href`() {
        val out = HtmlSanitizer.sanitize("""<a href="javascript:alert(1)">a</a><a href="data:text/html,x">b</a><a href="https://ok.example/">c</a>""", false).html
        assertFalse(out.contains("javascript", true))
        assertFalse(out.contains("data:", true))
        assertTrue(out.contains("https://ok.example/"))
        assertTrue(out.contains("noopener"))
    }

    @Test fun `remote images are blocked by default and counted`() {
        val html = """<img src="https://track.example/pixel.gif" width="1" height="1"><p>x</p>"""
        val blocked = HtmlSanitizer.sanitize(html, allowRemoteImages = false)
        assertFalse(blocked.html.contains("track.example"))
        assertEquals(1, blocked.blockedRemoteContent)
        assertTrue(HtmlSanitizer.sanitize(html, allowRemoteImages = true).html.contains("track.example"))
    }

    @Test fun `plain http images never load and cid images are resolved locally`() {
        val out = HtmlSanitizer.sanitize("""<img src="http://x.example/a.png"><img src="cid:logo1">""", allowRemoteImages = true) { cid ->
            if (cid == "logo1") "data:image/png;base64,AAAA" else null
        }.html
        assertFalse(out.contains("http://"))
        assertTrue(out.contains("data:image/png;base64,AAAA"))
    }
}

class LinkPolicyTest {
    @Test fun `only https mailto and tel are allowed`() {
        assertTrue(LinkPolicy.isSafe("https://example.com/x"))
        assertTrue(LinkPolicy.isSafe("mailto:a@b.ch"))
        assertTrue(LinkPolicy.isSafe("tel:+41791234567"))
        listOf("http://example.com", "javascript:alert(1)", "file:///etc/passwd", "intent://x#Intent;end", "data:text/html,x", "content://x", "not a url", "https://")
            .forEach { assertFalse(LinkPolicy.isSafe(it), it) }
    }
}

class HeaderSanitizerTest {
    @Test fun `line breaks in subject are neutralised`() {
        assertEquals("Hi Bcc: evil@x.ch", HeaderSanitizer.cleanValue("Hi\r\nBcc: evil@x.ch"))
    }

    @Test fun `addresses with injected headers are refused`() {
        assertFailsWith<IllegalArgumentException> { HeaderSanitizer.requireValidAddress("a@b.ch\r\nBcc: evil@x.ch") }
        assertFailsWith<IllegalArgumentException> { HeaderSanitizer.requireValidAddress("not-an-address") }
        assertEquals("a@b.ch", HeaderSanitizer.requireValidAddress("A <a@b.ch>").address)
    }
}

class FileNameSanitizerTest {
    @Test fun `path traversal and control characters are removed`() {
        assertEquals("passwd", FileNameSanitizer.sanitize("../../etc/passwd"))
        assertEquals("evil.txt", FileNameSanitizer.sanitize("..\\..\\evil.txt"))
        assertEquals("attachment", FileNameSanitizer.sanitize("..."))
        assertEquals("attachment", FileNameSanitizer.sanitize(null))
        assertEquals("a.pdf", FileNameSanitizer.sanitize("a\u0000.pdf"))
        assertTrue(FileNameSanitizer.sanitize("x".repeat(500) + ".pdf").length <= 120)
        assertTrue(FileNameSanitizer.sanitize("x".repeat(500) + ".pdf").endsWith(".pdf"))
    }

    @Test fun `executable types are flagged`() {
        assertTrue(FileNameSanitizer.isDangerous("setup.APK"))
        assertFalse(FileNameSanitizer.isDangerous("photo.jpg"))
    }
}
