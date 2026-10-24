package ch.imail.core.logo

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

class UnsafeTargetException(message: String) : Exception(message)

/** SSRF protection: only public, DNS-named hosts may be contacted. */
object UrlGuard {
    private val blockedSuffixes = listOf(".local", ".internal", ".localhost", ".lan", ".home", ".corp", ".intranet")

    fun isPublic(addr: InetAddress): Boolean {
        if (addr.isAnyLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isSiteLocalAddress || addr.isMulticastAddress
        ) return false
        val b = addr.address
        return when (addr) {
            is Inet4Address -> {
                val o0 = b[0].toInt() and 0xff
                val o1 = b[1].toInt() and 0xff
                !(o0 == 0 || o0 >= 224 || (o0 == 100 && o1 in 64..127) || (o0 == 192 && o1 == 0 && (b[2].toInt() and 0xff) == 0) ||
                    (o0 == 198 && o1 in 18..19))
            }
            is Inet6Address -> {
                val o0 = b[0].toInt() and 0xff
                val v4Mapped = b.take(10).all { it.toInt() == 0 } && (b[10].toInt() and 0xff) == 0xff && (b[11].toInt() and 0xff) == 0xff
                !(o0 and 0xfe == 0xfc /* ULA fc00::/7 */ || o0 == 0xfe && (b[1].toInt() and 0xc0) == 0x80 || v4Mapped ||
                    b.all { it.toInt() == 0 })
            }
            else -> false
        }
    }

    /** Resolves [host] and returns one address only if every answer is public; never accepts IP literals. */
    fun resolvePublic(host: String, resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() }): InetAddress {
        val h = host.lowercase().trimEnd('.')
        if (!h.contains('.') || h == "localhost" || blockedSuffixes.any { h.endsWith(it) }) throw UnsafeTargetException("Host nicht erlaubt")
        if (h.all { it.isDigit() || it == '.' || it == ':' } || h.contains(':') || h.startsWith("[")) throw UnsafeTargetException("IP-Adressen nicht erlaubt")
        val addrs = try { resolver(h) } catch (e: Exception) { throw UnsafeTargetException("DNS-Auflösung fehlgeschlagen") }
        if (addrs.isEmpty() || !addrs.all(::isPublic)) throw UnsafeTargetException("Zieladresse nicht öffentlich")
        return addrs.first()
    }
}
