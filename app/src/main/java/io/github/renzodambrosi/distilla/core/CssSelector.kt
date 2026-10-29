package io.github.renzodambrosi.distilla.core

import org.jsoup.Jsoup

/**
 * CSS selectors for the site rules, as supported by jsoup: tags, .class, #id, [attr],
 * [attr^=start], [attr*=part], combinators (div > p, article p) and comma-separated lists.
 *
 * One addition to standard CSS: "*" inside a class name is a wildcard, so
 * ".wp-block-*" matches every element with a class starting with "wp-block-".
 * It is translated to the attribute-regex selector jsoup understands.
 */
object CssSelector {

    private val WILDCARD_CLASS = Regex("""\.([A-Za-z0-9_-]*\*[A-Za-z0-9_*-]*)""")

    /** Selector ready for Element.select(), or null if [css] is empty. */
    fun toJsoup(css: String): String? {
        val trimmed = css.trim().trim(',').trim()
        if (trimmed.isEmpty()) return null
        return WILDCARD_CLASS.replace(trimmed) { m ->
            val glob = m.groupValues[1]
            val regex = glob.split('*').joinToString("[^ ]*")
            // Whole class token inside the space-separated class list
            "[class~=(^| )$regex( |$)]"
        }
    }

    fun isValid(css: String): Boolean {
        val q = toJsoup(css) ?: return true
        return runCatching { Jsoup.parse("<html><body><p class='a b'>x</p></body></html>").select(q); true }
            .getOrDefault(false)
    }
}
