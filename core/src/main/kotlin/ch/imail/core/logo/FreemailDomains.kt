package ch.imail.core.logo

import java.net.IDN

/** Knows which domains belong to consumer mail providers (no company logo) and derives a lookup domain. */
object FreemailDomains {
    private val exact = setOf(
        "gmail.com", "googlemail.com", "outlook.com", "hotmail.com", "live.com", "msn.com", "yahoo.com",
        "ymail.com", "icloud.com", "me.com", "mac.com", "aol.com", "web.de", "t-online.de", "freenet.de",
        "proton.me", "protonmail.com", "pm.me", "tutanota.com", "tuta.com", "tutamail.com", "mailbox.org",
        "posteo.de", "posteo.net", "bluewin.ch", "sunrise.ch", "hispeed.ch", "gmx.net", "gmx.de", "gmx.ch",
        "gmx.at", "gmx.com", "mail.com", "zoho.com", "yandex.com", "yandex.ru", "mail.ru", "qq.com", "163.com",
        "126.com", "orange.fr", "free.fr", "wanadoo.fr", "libero.it", "virgilio.it", "live.de", "outlook.de",
        "hotmail.de", "yahoo.de", "yahoo.fr", "yahoo.co.uk", "hotmail.co.uk", "hotmail.fr", "outlook.fr",
        "fastmail.com", "fastmail.fm", "hey.com", "duck.com",
    )
    private val twoLevelSuffixes = setOf("co", "com", "org", "net", "gov", "ac", "edu")
    private val label = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

    /** Lower-cased ASCII domain of an address, or null if it is not a plausible DNS name. */
    fun domainOf(address: String): String? {
        val raw = address.substringAfterLast('@', "").trim().trimEnd('>').lowercase()
        if (raw.isEmpty() || raw.length > 253) return null
        val ascii = try { IDN.toASCII(raw) } catch (_: Exception) { return null }
        val labels = ascii.split('.')
        return ascii.takeIf { labels.size >= 2 && labels.all { label.matches(it) } }
    }

    fun isFreemail(domain: String): Boolean {
        val d = domain.lowercase()
        if (d in exact) return true
        val parts = d.split('.')
        return parts.size >= 2 && parts[parts.size - 2].let { it == "gmx" || it == "hotmail" || it == "outlook" || it == "yahoo" }
    }

    /** Domains to try, most specific first (e.g. mail.firma.ch -> firma.ch). */
    fun lookupCandidates(domain: String): List<String> {
        val parts = domain.split('.')
        val out = mutableListOf(domain)
        if (parts.size > 2) {
            val keep = if (parts[parts.size - 2] in twoLevelSuffixes && parts.last().length == 2) 3 else 2
            if (parts.size > keep) out += parts.takeLast(keep).joinToString(".")
        }
        return out.distinct()
    }
}
