package ch.imail.core.oauth

import ch.imail.core.mail.ConnectionSecurity
import ch.imail.core.mail.ServerConfig

/** Static description of an OAuth2 mail provider (authorization code flow with PKCE, public client). */
data class OAuthProvider(
    val id: String,
    val displayName: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val scopes: List<String>,
    val imap: ServerConfig,
    val smtp: ServerConfig,
)

object OAuthProviders {
    val google = OAuthProvider(
        id = "google", displayName = "Google (Gmail)",
        authorizationEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
        tokenEndpoint = "https://oauth2.googleapis.com/token",
        scopes = listOf("https://mail.google.com/", "openid", "email"),
        imap = ServerConfig("imap.gmail.com", 993, ConnectionSecurity.SSL_TLS),
        smtp = ServerConfig("smtp.gmail.com", 465, ConnectionSecurity.SSL_TLS),
    )
    val microsoft = OAuthProvider(
        id = "microsoft", displayName = "Microsoft (Outlook / Microsoft 365)",
        authorizationEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
        tokenEndpoint = "https://login.microsoftonline.com/common/oauth2/v2.0/token",
        scopes = listOf(
            "https://outlook.office.com/IMAP.AccessAsUser.All", "https://outlook.office.com/SMTP.Send",
            "offline_access", "openid", "email",
        ),
        imap = ServerConfig("outlook.office365.com", 993, ConnectionSecurity.SSL_TLS),
        smtp = ServerConfig("smtp.office365.com", 587, ConnectionSecurity.STARTTLS),
    )

    val all = listOf(google, microsoft)
    fun byId(id: String?): OAuthProvider? = all.firstOrNull { it.id == id }

    /** Reads the mailbox address from an ID token's claims. The token comes straight from the TLS-protected token endpoint. */
    fun emailFromIdToken(idToken: String): String? {
        val payload = idToken.split('.').getOrNull(1) ?: return null
        val json = try { String(java.util.Base64.getUrlDecoder().decode(payload), Charsets.UTF_8) } catch (_: IllegalArgumentException) { return null }
        for (claim in listOf("email", "preferred_username")) {
            Regex("\"$claim\"\\s*:\\s*\"([^\"\\\\]+)\"").find(json)?.groupValues?.get(1)?.takeIf { '@' in it }?.let { return it }
        }
        return null
    }
}
