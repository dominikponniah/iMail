package ch.imail.app.data

import android.content.Context
import ch.imail.core.logo.CacheEntry
import ch.imail.core.logo.Logo
import ch.imail.core.logo.LogoCache
import java.io.File
import java.security.MessageDigest

/** Persists logos (and cached misses) in the app-private cache dir. File names are hashes of the domain, never the domain itself. */
class DiskLogoCache(context: Context, private val maxFiles: Int = 300) : LogoCache {
    private val dir = File(context.cacheDir, "logos").apply { mkdirs() }

    private fun file(domain: String) =
        File(dir, MessageDigest.getInstance("SHA-256").digest(domain.toByteArray()).joinToString("") { "%02x".format(it) }.take(32))

    @Synchronized override fun get(domain: String): CacheEntry? = runCatching {
        val f = file(domain).takeIf { it.isFile } ?: return null
        val raw = f.readBytes()
        if (raw.size < 9) return null
        val time = java.nio.ByteBuffer.wrap(raw, 0, 8).long
        if (raw[8].toInt() == 0) return CacheEntry(null, time)
        val mimeLen = raw[9].toInt()
        val mime = String(raw, 10, mimeLen, Charsets.US_ASCII)
        CacheEntry(Logo(raw.copyOfRange(10 + mimeLen, raw.size), mime), time)
    }.getOrNull()

    @Synchronized override fun put(domain: String, entry: CacheEntry) {
        runCatching {
            val logo = entry.logo
            val head = java.nio.ByteBuffer.allocate(8).putLong(entry.fetchedAtMs).array()
            val body = if (logo == null) byteArrayOf(0) else {
                val mime = logo.mimeType.toByteArray(Charsets.US_ASCII)
                byteArrayOf(1, mime.size.toByte()) + mime + logo.bytes
            }
            file(domain).writeBytes(head + body)
            val files = dir.listFiles().orEmpty()
            if (files.size > maxFiles) files.sortedBy { it.lastModified() }.take(files.size - maxFiles).forEach { it.delete() }
        }
    }
}
