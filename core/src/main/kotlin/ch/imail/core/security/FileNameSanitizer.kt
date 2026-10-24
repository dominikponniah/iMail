package ch.imail.core.security

/** Makes attachment names safe to use as local file names (no traversal, no hidden/control chars). */
object FileNameSanitizer {
    private const val MAX_LENGTH = 120
    private val dangerousExtensions = setOf(
        "apk", "exe", "bat", "cmd", "com", "scr", "msi", "js", "jse", "vbs", "vbe", "wsf",
        "ps1", "jar", "sh", "lnk", "dll", "reg", "hta", "pif",
    )

    fun sanitize(name: String?): String {
        val base = name.orEmpty()
            .substringAfterLast('/').substringAfterLast('\\')
            .filter { it >= ' ' && it != '\u007f' && it !in "<>:\"|?*" }
            .trim()
            .trimStart('.')
        if (base.isEmpty()) return "attachment"
        if (base.length <= MAX_LENGTH) return base
        val ext = base.substringAfterLast('.', "").take(10)
        val stem = base.substringBeforeLast('.', base)
        return if (ext.isEmpty()) base.take(MAX_LENGTH) else stem.take(MAX_LENGTH - ext.length - 1) + "." + ext
    }

    fun isDangerous(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in dangerousExtensions
}
