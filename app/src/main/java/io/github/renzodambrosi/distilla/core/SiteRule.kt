package io.github.renzodambrosi.distilla.core

import java.util.UUID

/** Where a rule comes from. USER rules are never changed by syncing without asking. */
enum class RuleSource { USER, REMOTE }

/** Which CSS filter runs first. See [ArticleExtractor.selectWithRule]. */
enum class FilterOrder(val json: String) {
    /** Take the included elements, then drop the excluded ones inside them. */
    INCLUDE_FIRST("include-first"),
    /** Drop the excluded elements from the page, then take the included ones that are left. */
    EXCLUDE_FIRST("exclude-first");

    companion object {
        fun fromJson(s: String?): FilterOrder = entries.firstOrNull { it.json.equals(s?.trim(), ignoreCase = true) } ?: INCLUDE_FIRST
    }
}

data class SiteRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** URL with wildcards: "*" = any sequence, "?" = one character. See [UrlMatcher]. */
    val pattern: String,
    /** XPath of the element(s) holding the story. Optional: empty = the whole page. */
    val xpath: String = "",
    /** Optional CSS selector: keep only these elements, e.g. ".wp-block-paragraph, h2". */
    val include: String = "",
    /** Optional CSS selector: drop these elements, e.g. ".ad, .share-box". */
    val exclude: String = "",
    val order: FilterOrder = FilterOrder.INCLUDE_FIRST,
    /** Optional User-Agent for this site only; empty = the one set in Settings. */
    val userAgent: String = "",
    /** Always load this site with the browser engine (WebView), e.g. when it blocks apps. */
    val useBrowser: Boolean = false,
    val source: RuleSource = RuleSource.USER,
    /**
     * For a USER rule: the Git configuration (see [signature]) the user chose to ignore
     * with "keep local". The same conflict is not asked again until Git changes it.
     */
    val ignoredRemote: String? = null,
) {
    val displayName: String get() = name.ifBlank { pattern }

    /** Comparison key: two rules with the same pattern are "the same URL". */
    val key: String get() = normalizePattern(pattern)

    /** A rule needs at least one way of choosing the text. */
    val hasSelection: Boolean get() = xpath.isNotBlank() || include.isNotBlank() || exclude.isNotBlank()

    /**
     * A rule is worth keeping if it chooses the text or at least changes how the page is
     * requested (User-Agent, browser engine): then the text is found by automatic detection.
     */
    val isUsable: Boolean get() = hasSelection || userAgent.isNotBlank() || useBrowser

    /**
     * Everything that decides which text is extracted, as one string, to compare
     * a local rule with the Git one. For an XPath-only rule it is just the XPath.
     */
    val signature: String
        get() {
            val base = if (include.isBlank() && exclude.isBlank() && order == FilterOrder.INCLUDE_FIRST) xpath.trim()
            else listOf(xpath.trim(), include.trim(), exclude.trim(), order.json).joinToString("\u0001")
            // A different User-Agent can make the site send different HTML, so it is part of the signature
            val withUa = if (userAgent.isBlank()) base else base + "\u0002" + userAgent.trim()
            return if (useBrowser) withUa + "\u0003browser" else withUa
        }

    companion object {
        fun normalizePattern(p: String): String = p.trim().lowercase()
    }
}
