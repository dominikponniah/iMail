package ch.imail.core

import ch.imail.core.logo.CacheEntry
import ch.imail.core.logo.Logo
import ch.imail.core.logo.LogoFetcher
import ch.imail.core.logo.LogoService
import ch.imail.core.logo.MemoryLogoCache
import ch.imail.core.logo.SafeHttpsClient
import ch.imail.core.logo.UnsafeTargetException
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LogoServiceTest {
    private class CountingFetcher(var result: Logo?) : LogoFetcher() {
        var calls = 0
        override fun fetch(domain: String): Logo? { calls++; return result }
    }

    private val png = Logo(byteArrayOf(1), "image/png")

    @Test fun `freemail senders never trigger a network lookup`() {
        val f = CountingFetcher(png)
        assertNull(LogoService(f).logoFor("max@gmail.com"))
        assertEquals(0, f.calls)
    }

    @Test fun `hits and misses are cached with their own TTL`() {
        var now = 0L
        val f = CountingFetcher(png)
        val svc = LogoService(f, listOf(MemoryLogoCache()), { now }, hitTtlMs = 1000, missTtlMs = 100)
        assertNotNull(svc.logoFor("a@firma.ch")); assertNotNull(svc.logoFor("b@firma.ch"))
        assertEquals(1, f.calls)
        now = 1001
        svc.logoFor("a@firma.ch")
        assertEquals(2, f.calls)

        val miss = CountingFetcher(null)
        val svc2 = LogoService(miss, listOf(MemoryLogoCache()), { now }, hitTtlMs = 1000, missTtlMs = 100)
        svc2.logoFor("a@nope.ch"); svc2.logoFor("a@nope.ch")
        assertEquals(1, miss.calls)
        now += 101
        svc2.logoFor("a@nope.ch")
        assertEquals(2, miss.calls)
    }

    @Test fun `fetcher never contacts private targets`() {
        var resolved = 0
        val client = SafeHttpsClient(resolver = { resolved++; throw UnsafeTargetException("blocked") })
        assertNull(LogoFetcher(client).fetch("internal-looking.example.com"))
        assertNull(client.get(URI.create("http://example.com/x")))
        assertNull(client.get(URI.create("https://example.com:8443/x")))
    }

    @Test fun `icon links are limited to the same domain and exclude svg`() {
        val html = """<link rel="icon" href="/a.svg"><link rel="icon" sizes="32x32" href="/f32.png">
            <link rel="apple-touch-icon" sizes="180x180" href="/apple.png"><link rel="icon" href="https://cdn.evil.example/x.png">
            <link rel="icon" href="http://firma.ch/insecure.png">"""
        val links = LogoFetcher().parseIconLinks(html, "https://firma.ch/", "firma.ch")
        assertEquals(listOf("https://firma.ch/apple.png", "https://firma.ch/f32.png"), links)
    }
}
