package ch.imail.core.logo

import java.util.concurrent.ConcurrentHashMap

/** A cached lookup result; [logo] == null is a cached miss. */
data class CacheEntry(val logo: Logo?, val fetchedAtMs: Long)

interface LogoCache {
    fun get(domain: String): CacheEntry?
    fun put(domain: String, entry: CacheEntry)
}

class MemoryLogoCache(private val maxEntries: Int = 128) : LogoCache {
    private val map = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?) = size > maxEntries
    }
    @Synchronized override fun get(domain: String) = map[domain]
    @Synchronized override fun put(domain: String, entry: CacheEntry) { map[domain] = entry }
}

/** Resolves a logo for a sender address: skips freemail domains, caches hits and misses with a TTL. Blocking; call off the main thread. */
class LogoService(
    private val fetcher: LogoFetcher,
    private val caches: List<LogoCache> = listOf(MemoryLogoCache()),
    private val now: () -> Long = System::currentTimeMillis,
    private val hitTtlMs: Long = 7L * 24 * 3600 * 1000,
    private val missTtlMs: Long = 24L * 3600 * 1000,
) {
    private val locks = ConcurrentHashMap<String, Any>()

    fun logoFor(address: String): Logo? {
        val domain = FreemailDomains.domainOf(address) ?: return null
        if (FreemailDomains.isFreemail(domain)) return null
        synchronized(locks.computeIfAbsent(domain) { Any() }) {
            for ((i, cache) in caches.withIndex()) {
                val e = cache.get(domain) ?: continue
                if (now() - e.fetchedAtMs < if (e.logo != null) hitTtlMs else missTtlMs) {
                    caches.take(i).forEach { it.put(domain, e) }
                    return e.logo
                }
            }
            val logo = try { fetcher.fetch(domain) } catch (_: Exception) { null }
            val entry = CacheEntry(logo, now())
            caches.forEach { it.put(domain, entry) }
            return logo
        }
    }
}
