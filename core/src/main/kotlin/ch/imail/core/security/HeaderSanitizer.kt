package ch.imail.core.security

import jakarta.mail.internet.AddressException
import jakarta.mail.internet.InternetAddress

/** Prevents CR/LF header injection and validates addresses before anything reaches SMTP. */
object HeaderSanitizer {
    private val forbidden = Regex("[\\r\\n\\u0000\\u2028\\u2029]+")

    /** Strips line breaks and NULs from a free-text header value such as the subject. */
    fun cleanValue(value: String): String = value.replace(forbidden, " ").trim()

    /** Returns the validated address, or throws if it contains control characters or is malformed. */
    fun requireValidAddress(raw: String): InternetAddress {
        require(!forbidden.containsMatchIn(raw)) { "Adresse enthält ungültige Zeichen" }
        try {
            val addr = InternetAddress(raw.trim(), true)
            addr.validate()
            return addr
        } catch (e: AddressException) {
            throw IllegalArgumentException("Ungültige E-Mail-Adresse", e)
        }
    }
}
