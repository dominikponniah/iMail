package ch.imail.core.mail

import java.util.Properties

/** Exposes the effective TLS/auth properties of a session (used by tests and diagnostics). */
object MailSessionAccess {
    fun session(protocol: String, cfg: ServerConfig, auth: AuthType): Properties = MailSession.create(protocol, cfg, null, auth).properties
}
