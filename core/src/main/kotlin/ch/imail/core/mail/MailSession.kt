package ch.imail.core.mail

import jakarta.mail.Session
import java.util.Properties
import javax.net.ssl.SSLSocketFactory

/** Test hook only: production code never overrides trust or host-name checking. */
internal data class TlsOverrides(val socketFactory: SSLSocketFactory, val checkServerIdentity: Boolean)

/** Builds Jakarta Mail sessions that enforce TLS >= 1.2, certificate and host-name validation, and no plaintext fallback. */
internal object MailSession {
    private const val PROTOCOLS = "TLSv1.3 TLSv1.2"
    private const val TIMEOUT_MS = "20000"

    fun create(protocol: String, cfg: ServerConfig, overrides: TlsOverrides? = null, auth: AuthType = AuthType.PASSWORD): Session {
        require(protocol == "imap" || protocol == "smtp")
        val p = "mail.$protocol"
        val props = Properties().apply {
            put("$p.host", cfg.host)
            put("$p.port", cfg.port.toString())
            put("$p.connectiontimeout", TIMEOUT_MS)
            put("$p.timeout", TIMEOUT_MS)
            put("$p.writetimeout", TIMEOUT_MS)
            put("$p.ssl.protocols", PROTOCOLS)
            put("$p.ssl.checkserveridentity", (overrides?.checkServerIdentity ?: true).toString())
            when (cfg.security) {
                ConnectionSecurity.SSL_TLS -> put("$p.ssl.enable", "true")
                ConnectionSecurity.STARTTLS -> {
                    put("$p.starttls.enable", "true")
                    put("$p.starttls.required", "true")
                }
            }
            overrides?.let { put("$p.ssl.socketFactory", it.socketFactory) }
            if (protocol == "smtp") put("mail.smtp.auth", "true")
            // OAuth2: only XOAUTH2 may be used, so a token is never offered through PLAIN/LOGIN.
            if (auth == AuthType.OAUTH2) put("$p.auth.mechanisms", "XOAUTH2")
            // Reading a preview must not mark the message as read.
            if (protocol == "imap") put("mail.imap.peek", "true")
            put("mail.mime.address.strict", "true")
        }
        return Session.getInstance(props)
    }
}
