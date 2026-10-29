package io.github.renzodambrosi.distilla.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.min

enum class ExtractionMethod { RULE, AUTO, JSON_LD, FULL_PAGE }

/** Notes for the reader. The UI turns them into localized text. */
sealed interface ArticleWarning {
    /** The site rule matched the URL but its XPath found nothing. */
    data class RuleNoMatch(val ruleName: String) : ArticleWarning
    /** No article block found: the whole page is shown. */
    data object FullPage : ArticleWarning
}

data class Article(
    val requestedUrl: String,
    val url: String,
    val title: String,
    val text: String,
    val method: ExtractionMethod,
    val rule: SiteRule? = null,
    /** XPath of the block chosen by automatic detection: a starting point for a new rule. */
    val suggestedXpath: String? = null,
    val warnings: List<ArticleWarning> = emptyList(),
    /** Page language (BCP 47 tag from <html lang> or og:locale), used to pick the voice. */
    val language: String? = null,
    /** The page was loaded with the browser engine (WebView) instead of a plain download. */
    val loadedWithBrowser: Boolean = false,
) {
    val host: String get() = UrlMatcher.hostOf(url) ?: url

    /** Testo pronto da copiare o condividere. */
    val shareText: String
        get() = buildString {
            if (title.isNotBlank()) append(title).append("\n\n")
            append(text)
            append("\n\n").append(url)
        }
}

/**
 * Estrazione del testo della notizia:
 *  1. se c'è una regola per l'URL → usa il suo XPath;
 *  2. altrimenti punteggio dei blocchi di testo (algoritmo tipo Readability);
 *  3. se il risultato è scarso → articleBody dai dati strutturati JSON-LD;
 *  4. in ultima istanza → tutta la pagina ripulita.
 */
object ArticleExtractor {

    /** Default User-Agent: Chrome on an Android phone, so sites send their normal mobile page. */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/129.0.0.0 Mobile Safari/537.36"

    /** Preset for sites that send a reduced page to phones. */
    const val USER_AGENT_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/129.0.0.0 Safari/537.36"

    /** The User-Agent to send: the site's own if set, else the app's, else the default. */
    fun effectiveUserAgent(rule: SiteRule?, appUserAgent: String): String =
        rule?.userAgent?.trim()?.takeIf { it.isNotEmpty() }
            ?: appUserAgent.trim().takeIf { it.isNotEmpty() }
            ?: USER_AGENT

    /**
     * Plain HTTP download. HTTP errors become [HttpErrorException], which carries the
     * status and the "Server" header so a block by an anti-bot service can be recognised.
     */
    fun fetch(url: String, userAgent: String = USER_AGENT): Document {
        val res = Jsoup.connect(url)
            .userAgent(userAgent.ifBlank { USER_AGENT })
            .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "it-IT,it;q=0.9,en;q=0.8")
            .followRedirects(true)
            .ignoreHttpErrors(true)
            .timeout(20_000)
            .maxBodySize(15 * 1024 * 1024)
            .execute()
        if (res.statusCode() >= 400) throw HttpErrorException(res.statusCode(), res.header("Server"), url)
        return res.parse()
    }

    fun extract(doc: Document, requestedUrl: String, rule: SiteRule?): Article {
        val url = doc.location().ifBlank { requestedUrl }
        val title = extractTitle(doc)
        val lang = extractLanguage(doc)
        val warnings = mutableListOf<ArticleWarning>()

        // A rule with only a User-Agent doesn't choose the text: automatic detection below
        if (rule != null && rule.hasSelection) {
            val text = runCatching { renderRule(doc, rule) }.getOrNull()
            if (!text.isNullOrBlank()) {
                return Article(requestedUrl, url, title, stripTitle(text, title), ExtractionMethod.RULE, rule, language = lang)
            }
            warnings += ArticleWarning.RuleNoMatch(rule.displayName)
        }

        val auto = runCatching { autoDetect(doc) }.getOrNull()
        val autoText = auto?.let { TextRenderer.render(it.elements) }.orEmpty()
        val ldText = runCatching { jsonLdBody(doc) }.getOrNull().orEmpty()

        return when {
            autoText.isNotBlank() && (autoText.length >= 500 || autoText.length >= ldText.length) ->
                Article(requestedUrl, url, title, stripTitle(autoText, title), ExtractionMethod.AUTO, null, auto?.xpath, warnings, lang)

            ldText.isNotBlank() ->
                Article(requestedUrl, url, title, stripTitle(ldText, title), ExtractionMethod.JSON_LD, null, auto?.xpath, warnings, lang)

            else -> {
                val body = doc.body()?.clone()?.also { removeJunk(it) }
                val text = body?.let { TextRenderer.render(listOf(it)) }.orEmpty()
                Article(
                    requestedUrl, url, title, text, ExtractionMethod.FULL_PAGE, null, null,
                    warnings + ArticleWarning.FullPage, lang,
                )
            }
        }
    }

    // ---------------------------------------------------------------- XPath

    fun isValidXpath(xpath: String): Boolean =
        xpath.isNotBlank() && runCatching { Jsoup.parse("<html><body></body></html>").selectXpath(xpath); true }.getOrDefault(false)

    /** Elementi trovati dall'XPath, scartando quelli già contenuti in un altro risultato. */
    fun selectXpath(doc: Document, xpath: String): List<Element> {
        val found = doc.selectXpath(xpath)
        return found.filter { el -> found.none { other -> other !== el && el.parents().any { it === other } } }
    }

    fun renderXpath(doc: Document, xpath: String): String =
        TextRenderer.render(selectXpath(doc, xpath).map { it.clone().also(::removeJunk) })

    // ---------------------------------------------------------------- site rule: XPath + CSS filters

    /**
     * Elements chosen by a site rule:
     *  1. the elements found by the XPath, or the whole page if the XPath is empty;
     *  2. the CSS filters, in the order chosen in the rule:
     *     - include: keep only the elements matching the selector (e.g. ".wp-block-paragraph, h2");
     *     - exclude: drop the elements matching the selector (e.g. ".ad, .share-box").
     *
     * INCLUDE_FIRST takes the included elements even when they sit inside an excluded
     * container, then cleans them; EXCLUDE_FIRST first removes the excluded containers,
     * so whatever was inside them is lost even if it matches the include selector.
     *
     * The page itself is never modified: work happens on copies.
     */
    fun selectWithRule(doc: Document, rule: SiteRule): List<Element> {
        val roots: List<Element> =
            if (rule.xpath.isBlank()) listOfNotNull(doc.body()?.clone())
            else selectXpath(doc, rule.xpath).map { it.clone() }
        val include = CssSelector.toJsoup(rule.include)
        val exclude = CssSelector.toJsoup(rule.exclude)

        fun applyInclude(els: List<Element>): List<Element> =
            if (include == null) els
            else dedupNested(els.flatMap { it.select(include) }).map { it.clone() }

        fun applyExclude(els: List<Element>): List<Element> {
            if (exclude == null) return els
            return els.filterNot { it.`is`(exclude) }.onEach { it.removeAll(exclude) }
        }

        val result = when (rule.order) {
            FilterOrder.INCLUDE_FIRST -> applyExclude(applyInclude(roots))
            FilterOrder.EXCLUDE_FIRST -> applyInclude(applyExclude(roots))
        }
        return result.onEach(::removeJunk)
    }

    fun renderRule(doc: Document, rule: SiteRule): String = TextRenderer.render(selectWithRule(doc, rule))

    private fun dedupNested(found: List<Element>): List<Element> =
        found.filter { el -> found.none { other -> other !== el && el.parents().any { it === other } } }

    // ---------------------------------------------------------------- pulizia

    private const val JUNK_TAGS =
        "script, style, noscript, iframe, form, button, input, select, textarea, svg, canvas, video, " +
            "audio, picture, img, figure, nav, aside, template, object, embed, link, meta"

    private fun Element.removeAll(query: String) {
        select(query).filter { it !== this && it.parent() != null }.forEach { it.remove() }
    }

    internal fun removeJunk(root: Element) {
        root.removeAll(JUNK_TAGS)
        root.removeAll("[hidden], [aria-hidden=true]")
        root.removeAll("[style~=(?i)display\\s*:\\s*none]")
    }

    // ---------------------------------------------------------------- rilevamento automatico

    private val UNLIKELY = Regex(
        "banner|breadcrumb|combx|comment|community|disqus|extra|footer|gdpr|legends|menu|related|remark|" +
            "replies|rss|shoutbox|sidebar|skyscraper|social|sponsor|supplemental|ad-break|agegate|pagination|" +
            "pager|popup|cookie|newsletter|correlat|share|sharing|condivid|commenti|consent|outbrain|taboola|teads",
        RegexOption.IGNORE_CASE,
    )
    private val MAYBE = Regex("and|article|body|column|content|main|shadow|testo|articolo|notizia|story", RegexOption.IGNORE_CASE)
    private val POSITIVE = Regex(
        "article|body|content|entry|hentry|h-entry|main|page|post|text|blog|story|testo|articolo|notizia|corpo|contenuto",
        RegexOption.IGNORE_CASE,
    )
    private val NEGATIVE = Regex(
        "-ad-|^ad-|\\bad\\b|\\badv|advert|banner|combx|comment|com-|contact|foot|footer|footnote|gdpr|masthead|" +
            "media|meta|outbrain|promo|related|scroll|share|shoutbox|sidebar|skyscraper|sponsor|shopping|tags|" +
            "tool|widget|social|newsletter|correlat|taboola|cookie|byline|didascalia|caption",
        RegexOption.IGNORE_CASE,
    )
    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "table", "ul", "ol", "dl", "pre", "blockquote",
        "h1", "h2", "h3", "h4", "h5", "h6", "header", "footer", "main", "figure", "form",
    )
    private val DYNAMIC = Regex("\\d{3,}")

    private class AutoResult(val elements: List<Element>, val xpath: String?)

    private fun classWeight(e: Element): Int {
        var w = 0
        for (s in listOf(e.className(), e.id())) {
            if (s.isEmpty()) continue
            if (NEGATIVE.containsMatchIn(s)) w -= 25
            if (POSITIVE.containsMatchIn(s)) w += 25
        }
        return w
    }

    private fun initScore(e: Element): Double {
        val base = when (e.normalName()) {
            "article" -> 10
            "div", "main" -> 5
            "section", "pre", "td", "blockquote" -> 3
            "address", "ol", "ul", "dl", "dd", "dt", "li", "form" -> -3
            "h1", "h2", "h3", "h4", "h5", "h6", "th" -> -5
            else -> 0
        }
        return (base + classWeight(e)).toDouble()
    }

    private fun linkDensity(e: Element): Double {
        val len = e.text().length
        if (len == 0) return 0.0
        return e.select("a").sumOf { it.text().length }.toDouble() / len
    }

    private fun autoDetect(doc: Document): AutoResult? {
        val body = doc.body()?.clone() ?: return null
        removeJunk(body)
        body.removeAll("header, footer")
        body.allElements
            .filter { it !== body && it.normalName() !in setOf("body", "article", "main", "a") }
            .filter {
                val m = it.className() + " " + it.id()
                UNLIKELY.containsMatchIn(m) && !MAYBE.containsMatchIn(m) && it.selectFirst("article") == null
            }
            .forEach { if (it.parent() != null) it.remove() }

        // 1. punteggio ai contenitori dei paragrafi
        val scores = IdentityHashMap<Element, Double>()
        val paragraphs = body.select("p, pre, blockquote, td").toMutableList<Element>()
        body.select("div, section").filterTo(paragraphs) { d ->
            d.children().none { it.normalName() in BLOCK_TAGS } && d.text().length >= 80
        }
        for (p in paragraphs) {
            val text = p.text()
            if (text.length < 25) continue
            val ancestors = generateSequence(p.parent()) { it.parent() }.take(3).toList()
            if (ancestors.isEmpty()) continue
            val score = 1.0 + text.count { it == ',' } + min(text.length / 100.0, 3.0)
            ancestors.forEachIndexed { level, a ->
                val divider = when (level) { 0 -> 1.0; 1 -> 2.0; else -> level * 3.0 }
                scores[a] = (scores[a] ?: initScore(a)) + score / divider
            }
        }
        if (scores.isEmpty()) return null

        // 2. penalità per i blocchi pieni di link
        val final = IdentityHashMap<Element, Double>()
        for ((e, s) in scores) final[e] = s * (1 - linkDensity(e))
        val (top, topScore) = final.entries.maxByOrNull { it.value }!!.let { it.key to it.value }

        // 3. aggiunge i fratelli che sembrano parte dello stesso articolo
        val threshold = max(10.0, topScore * 0.2)
        val parent = top.parent()
        val picked = mutableListOf<Element>()
        if (parent == null) picked += top
        else for (sib in parent.children()) {
            if (sib === top) { picked += sib; continue }
            val bonus = if (top.className().isNotEmpty() && sib.className() == top.className()) topScore * 0.2 else 0.0
            val s = final[sib]
            val append = when {
                s != null && s + bonus >= threshold -> true
                sib.normalName() == "p" -> {
                    val len = sib.text().length
                    val ld = linkDensity(sib)
                    (len > 80 && ld < 0.25) || (len in 1..80 && ld == 0.0 && Regex("\\.( |$)").containsMatchIn(sib.text()))
                }
                else -> false
            }
            if (append) picked += sib
        }

        val xpath = suggestXpath(if (picked.size > 1 && parent != null) parent else top)
        return AutoResult(picked.map { it.clone().also(::cleanConditionally) }, xpath)
    }

    /** Toglie dal contenitore scelto i sotto-blocchi che sembrano box, menu o elenchi di link. */
    private fun cleanConditionally(root: Element) {
        for (e in root.select("div, section, ul, ol, table, header, footer")) {
            if (e === root || e.parent() == null) continue
            val textLen = e.text().length
            val ld = linkDensity(e)
            val remove = (classWeight(e) < 0 && e.select("p").size < 3) ||
                (ld > 0.5 && textLen < 1000) ||
                (e.normalName() in setOf("ul", "ol") && ld > 0.3) ||
                textLen == 0
            if (remove) e.remove()
        }
    }

    /** Costruisce un XPath leggibile per l'elemento: preferisce id, poi classi "parlanti". */
    internal fun suggestXpath(e: Element): String? {
        val tag = e.normalName()
        val id = e.id()
        if (id.isNotBlank() && !DYNAMIC.containsMatchIn(id) && '\'' !in id) return "//*[@id='$id']"
        val classes = e.classNames().filter { it.isNotBlank() && !DYNAMIC.containsMatchIn(it) && '\'' !in it }
        val cls = classes.firstOrNull { POSITIVE.containsMatchIn(it) } ?: classes.firstOrNull()
        if (cls != null) return "//$tag[contains(concat(' ', normalize-space(@class), ' '), ' $cls ')]"
        if (tag == "article" || tag == "main") return "//$tag"
        val parent = e.parent() ?: return null
        val parentXp = suggestXpath(parent) ?: return null
        val index = parent.children().filter { it.normalName() == tag }.indexOfFirst { it === e } + 1
        return "$parentXp/$tag[$index]"
    }

    // ---------------------------------------------------------------- titolo e JSON-LD

    private fun extractTitle(doc: Document): String {
        doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        return doc.title().trim()
    }

    private fun extractLanguage(doc: Document): String? {
        val htmlLang = doc.selectFirst("html")?.attr("lang")?.trim().orEmpty()
        if (htmlLang.isNotEmpty()) return htmlLang.replace('_', '-')
        val og = doc.selectFirst("meta[property=og:locale]")?.attr("content")?.trim().orEmpty()
        return og.ifEmpty { null }?.replace('_', '-')
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** Se il testo inizia ripetendo il titolo, lo toglie (il titolo è già mostrato a parte). */
    private fun stripTitle(text: String, title: String): String {
        if (title.isBlank()) return text
        val first = text.substringBefore("\n\n")
        if (norm(first) != norm(title)) return text
        return text.substringAfter("\n\n", "").trim().ifBlank { text }
    }

    private fun jsonLdBody(doc: Document): String? {
        var best: String? = null
        for (script in doc.select("script[type=application/ld+json]")) {
            val el = runCatching { Json.parseToJsonElement(script.data().trim()) }.getOrNull() ?: continue
            val body = findArticleBody(el, 0) ?: continue
            if (body.length > (best?.length ?: 0)) best = body
        }
        val raw = best ?: return null
        return if (Regex("<(p|br|div)[\\s>/]", RegexOption.IGNORE_CASE).containsMatchIn(raw)) {
            TextRenderer.render(listOf(Jsoup.parseBodyFragment(raw).body()))
        } else {
            TextRenderer.normalizePlain(Parser.unescapeEntities(raw, false))
        }
    }

    private fun findArticleBody(el: JsonElement, depth: Int): String? {
        if (depth > 8) return null
        return when (el) {
            is JsonObject -> {
                (el["articleBody"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: el.values.mapNotNull { findArticleBody(it, depth + 1) }.maxByOrNull { it.length }
            }
            is JsonArray -> el.mapNotNull { findArticleBody(it, depth + 1) }.maxByOrNull { it.length }
            else -> null
        }
    }
}
