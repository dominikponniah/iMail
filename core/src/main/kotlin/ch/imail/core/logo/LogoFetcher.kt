package ch.imail.core.logo

import org.jsoup.Jsoup
import java.net.URI

data class Logo(val bytes: ByteArray, val mimeType: String)

/** Recognises raster image formats by magic bytes. SVG is deliberately not accepted (script risk). */
object ImageSniffer {
    fun sniff(b: ByteArray): String? = when {
        b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte() -> "image/png"
        b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte() -> "image/jpeg"
        b.size >= 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() && b[3] == '8'.code.toByte() -> "image/gif"
        b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
        b.size >= 6 && b[0].toInt() == 0 && b[1].toInt() == 0 && b[2].toInt() == 1 && b[3].toInt() == 0 -> "image/x-icon"
        else -> null
    }
}

/** Finds a company logo directly on the sender's domain (apple-touch-icon, page icon links, favicon.ico). No third-party service. */
open class LogoFetcher(private val client: SafeHttpsClient = SafeHttpsClient(), private val maxRequests: Int = 6) {

    open fun fetch(domain: String): Logo? {
        var budget = maxRequests
        for (candidate in FreemailDomains.lookupCandidates(domain)) {
            for (url in iconUrls(candidate) { if (budget > 1) { budget--; true } else false }) {
                if (budget <= 0) return null
                budget--
                val res = client.get(URI.create(url)) ?: continue
                val mime = ImageSniffer.sniff(res.body) ?: continue
                return Logo(res.body, mime)
            }
        }
        return null
    }

    /** Lazily yields candidate icon URLs; the HTML page is only fetched if the well-known paths fail. */
    private fun iconUrls(domain: String, mayFetch: () -> Boolean): Sequence<String> = sequence {
        yield("https://$domain/apple-touch-icon.png")
        if (mayFetch()) {
            val page = client.get(URI.create("https://$domain/"), accept = "text/html")
            if (page != null) yieldAll(parseIconLinks(page.body.toString(Charsets.UTF_8), "https://$domain/", domain))
        }
        yield("https://$domain/favicon.ico")
    }

    internal fun parseIconLinks(html: String, baseUrl: String, domain: String): List<String> {
        val doc = Jsoup.parse(html, baseUrl)
        return doc.select("link[rel~=(?i)icon][href]")
            .sortedByDescending { l ->
                val apple = if (l.attr("rel").contains("apple", true)) 1000 else 0
                apple + (l.attr("sizes").substringBefore('x').toIntOrNull() ?: 0)
            }
            .mapNotNull { l ->
                val abs = l.absUrl("href")
                val uri = try { URI(abs) } catch (_: Exception) { null }
                abs.takeIf { uri?.scheme.equals("https", true) && (uri?.host == domain || uri?.host?.endsWith(".$domain") == true) && !abs.endsWith(".svg", true) }
            }
            .take(3)
    }
}
