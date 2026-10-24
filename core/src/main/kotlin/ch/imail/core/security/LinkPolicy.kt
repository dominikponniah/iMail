package ch.imail.core.security

import java.net.URI

/** Decides which links in a mail may be opened. */
object LinkPolicy {
    private val allowedSchemes = setOf("https", "mailto", "tel")

    fun isSafe(url: String): Boolean {
        val uri = try { URI(url.trim()) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme !in allowedSchemes) return false
        return scheme != "https" || !uri.host.isNullOrEmpty()
    }

    /** Host shown to the user before opening a web link; null for non-web links. */
    fun displayHost(url: String): String? =
        try { URI(url.trim()).takeIf { it.scheme.equals("https", true) }?.host } catch (_: Exception) { null }
}
