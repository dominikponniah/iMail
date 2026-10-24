package ch.imail.core.logo

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class HttpResult(val status: Int, val headers: Map<String, String>, val body: ByteArray)

class ResponseTooLargeException : IOException("Antwort zu gross")

/** Minimal HTTP/1.0 response parser with hard size limits. */
object HttpResponseParser {
    private const val MAX_HEADER_BYTES = 16 * 1024

    fun parse(input: InputStream, maxBodyBytes: Int): HttpResult {
        val head = ByteArrayOutputStream()
        var matched = 0
        val terminator = byteArrayOf(13, 10, 13, 10)
        while (matched < 4) {
            val b = input.read()
            if (b < 0) throw IOException("Unerwartetes Ende der Antwort")
            if (head.size() >= MAX_HEADER_BYTES) throw ResponseTooLargeException()
            head.write(b)
            matched = if (b.toByte() == terminator[matched]) matched + 1 else if (b.toByte() == terminator[0]) 1 else 0
        }
        val lines = head.toString(Charsets.ISO_8859_1).split("\r\n").filter { it.isNotEmpty() }
        val status = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: throw IOException("Ungültige Statuszeile")
        val headers = lines.drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
        if (headers["transfer-encoding"]?.contains("chunked", true) == true) throw IOException("Chunked nicht unterstützt")
        headers["content-length"]?.toLongOrNull()?.let { if (it > maxBodyBytes) throw ResponseTooLargeException() }
        val body = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (body.size() + n > maxBodyBytes) throw ResponseTooLargeException()
            body.write(buf, 0, n)
        }
        return HttpResult(status, headers, body.toByteArray())
    }
}

/**
 * HTTPS-only GET with SSRF protection: the target IP is validated and then connected to directly
 * (no second DNS lookup, so no DNS rebinding), the certificate and host name are verified, redirects are re-validated.
 */
class SafeHttpsClient(
    private val resolver: (String) -> InetAddress = { UrlGuard.resolvePublic(it) },
    private val socketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val timeoutMs: Int = 5000,
    private val maxBodyBytes: Int = 256 * 1024,
    private val maxRedirects: Int = 3,
) {
    fun get(start: URI, accept: String = "image/*"): HttpResult? {
        var uri = start
        repeat(maxRedirects + 1) {
            val result = try { single(uri, accept) } catch (_: IOException) { return null } catch (_: UnsafeTargetException) { return null }
            if (result.status in 301..308 && result.status != 304) {
                val loc = result.headers["location"] ?: return null
                uri = try { uri.resolve(loc.trim()) } catch (_: Exception) { return null }
                return@repeat
            }
            return result.takeIf { it.status == 200 }
        }
        return null
    }

    private fun single(uri: URI, accept: String): HttpResult {
        if (!uri.scheme.equals("https", true)) throw UnsafeTargetException("Nur https")
        val host = uri.host ?: throw UnsafeTargetException("Kein Host")
        val port = if (uri.port == -1) 443 else uri.port
        if (port != 443) throw UnsafeTargetException("Nur Port 443")
        val address = resolver(host)
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(address, port), timeoutMs)
            raw.soTimeout = timeoutMs
            val ssl = socketFactory.createSocket(raw, host, port, true) as SSLSocket
            val params = ssl.sslParameters
            params.protocols = arrayOf("TLSv1.3", "TLSv1.2")
            params.endpointIdentificationAlgorithm = "HTTPS"
            params.serverNames = listOf(SNIHostName(host))
            ssl.sslParameters = params
            ssl.startHandshake()
            val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            val request = "GET $path HTTP/1.0\r\nHost: $host\r\nUser-Agent: iMail\r\nAccept: $accept\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n"
            ssl.outputStream.apply { write(request.toByteArray(Charsets.ISO_8859_1)); flush() }
            return HttpResponseParser.parse(ssl.inputStream, maxBodyBytes)
        } finally {
            try { raw.close() } catch (_: IOException) {}
        }
    }
}
