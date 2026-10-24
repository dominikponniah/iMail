package ch.imail.core.security

import org.jsoup.Jsoup
import org.jsoup.safety.Safelist

data class SanitizedHtml(val html: String, val blockedRemoteContent: Int)

/**
 * Allowlist-based sanitizer for HTML mail. Scripts, forms, iframes, styles and event handlers are removed.
 * Remote images (tracking pixels) are dropped unless [allowRemoteImages] is set; `cid:` images are resolved locally.
 */
object HtmlSanitizer {
    private val safelist: Safelist = Safelist.relaxed()
        .removeTags("img").addTags("img")
        .addAttributes("img", "src", "alt", "width", "height")
        .addProtocols("img", "src", "https", "cid")
        .removeProtocols("a", "href", "ftp").addProtocols("a", "href", "https", "mailto", "tel")
        .addAttributes("table", "width", "border", "cellpadding", "cellspacing", "bgcolor", "align")
        .addAttributes("td", "width", "height", "bgcolor", "align", "valign", "colspan", "rowspan")
        .addAttributes("tr", "bgcolor", "align", "valign")
        .addAttributes("th", "width", "bgcolor", "align", "valign", "colspan", "rowspan")
        .addEnforcedAttribute("a", "rel", "noopener noreferrer nofollow")
        .addEnforcedAttribute("a", "target", "_blank")

    /** [cidResolver] maps a Content-ID to a `data:` URI (or null if unknown). */
    fun sanitize(
        html: String,
        allowRemoteImages: Boolean,
        cidResolver: (String) -> String? = { null },
    ): SanitizedHtml {
        val cleaned = Jsoup.clean(html, "", safelist, org.jsoup.nodes.Document.OutputSettings().prettyPrint(false))
        val doc = Jsoup.parseBodyFragment(cleaned)
        var blocked = 0
        for (img in doc.select("img")) {
            val src = img.attr("src")
            when {
                src.startsWith("cid:", ignoreCase = true) -> {
                    val data = cidResolver(src.substring(4).trim('<', '>', ' '))
                    if (data != null) img.attr("src", data) else img.remove()
                }
                src.startsWith("https://", ignoreCase = true) -> if (!allowRemoteImages) {
                    blocked++
                    img.remove()
                }
                else -> img.remove()
            }
        }
        for (a in doc.select("a[href]")) if (!LinkPolicy.isSafe(a.attr("href"))) a.removeAttr("href")
        return SanitizedHtml(doc.body().html(), blocked)
    }
}
