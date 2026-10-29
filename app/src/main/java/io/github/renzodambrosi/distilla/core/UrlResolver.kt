package io.github.renzodambrosi.distilla.core

import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder

/**
 * Turns shortened and "wrapper" links into the real address of the story, before
 * anything else happens: rules, history, User-Agent and browser engine all then work
 * on the newspaper's URL instead of share.google/xyz.
 *
 * Two kinds of links are handled:
 *  - wrappers that carry the destination in the URL itself (google.com/url?q=...,
 *    search.app?link=..., l.facebook.com/l.php?u=...): decoded without any network request;
 *  - shorteners (share.google, bit.ly, t.co...): followed one hop at a time, reading the
 *    HTTP redirect or, when the shortener answers with a page, its meta refresh / script.
 */
object UrlResolver {

    /** Hosts (and their subdomains) that only redirect somewhere else. */
    val SHORTENERS: Set<String> = setOf(
        // Google
        "share.google", "search.app", "goo.gl", "g.co", "page.link", "app.goo.gl",
        // general purpose
        "bit.ly", "bitly.com", "j.mp", "tinyurl.com", "is.gd", "v.gd", "ow.ly", "buff.ly",
        "t.ly", "tiny.cc", "rb.gy", "cutt.ly", "shorturl.at", "rebrand.ly", "bit.do", "s.id",
        "shorte.st", "soo.gd", "clck.ru", "dlvr.it", "ift.tt", "trib.al", "spr.ly", "lnkd.in",
        "flip.it", "wp.me", "apple.news",
        // social networks
        "t.co", "fb.me", "l.facebook.com", "lm.facebook.com", "l.instagram.com", "l.threads.net",
        "out.reddit.com", "redd.it",
    )

    /** Wrapper hosts/paths whose destination is in a query parameter. */
    private data class Wrapper(val host: (String) -> Boolean, val path: (String) -> Boolean, val params: List<String>)

    private val WRAPPERS = listOf(
        // google.com/url?q=..., google.it/url?url=...
        Wrapper({ it.matches(Regex("""(www\.)?google\.[a-z.]+""")) }, { it == "/url" }, listOf("q", "url")),
        Wrapper({ it == "search.app" || it.endsWith(".search.app") }, { true }, listOf("link")),
        Wrapper({ it.endsWith("page.link") || it == "app.goo.gl" }, { true }, listOf("link")),
        Wrapper({ it in setOf("l.facebook.com", "lm.facebook.com") }, { it.startsWith("/l.php") }, listOf("u")),
        Wrapper({ it == "l.instagram.com" || it == "l.threads.net" }, { true }, listOf("u")),
        Wrapper({ it == "out.reddit.com" }, { true }, listOf("url")),
    )

    private const val MAX_HOPS = 10

    private fun host(url: String) = UrlMatcher.hostOf(url)?.removePrefix("www.")

    fun isShortener(url: String): Boolean {
        val h = host(url) ?: return false
        return SHORTENERS.any { h == it || h.endsWith(".$it") }
    }

    /**
     * Destination carried in the URL of a wrapper link, decoded; null if [url] isn't a
     * wrapper or the parameter is missing. No network access.
     */
    fun unwrap(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val h = uri.host?.lowercase() ?: return null
        val path = uri.rawPath.orEmpty()
        val wrapper = WRAPPERS.firstOrNull { it.host(h) && it.path(path) } ?: return null
        val query = queryParams(uri.rawQuery ?: return null)
        return wrapper.params.firstNotNullOfOrNull { p ->
            query[p]?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
        }
    }

    private fun queryParams(raw: String): Map<String, String> =
        raw.split('&').mapNotNull { part ->
            val k = part.substringBefore('=')
            val v = part.substringAfter('=', "")
            if (k.isEmpty()) null
            else runCatching { URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8") }.getOrNull()
        }.toMap()

    private val META_REFRESH = Regex("""^\s*\d*\s*;?\s*url\s*=\s*['"]?([^'"]+)""", RegexOption.IGNORE_CASE)
    private val JS_LOCATION = Regex(
        """(?:window\.|document\.|top\.)?location(?:\.href)?\s*(?:=|\.replace\(|\.assign\()\s*['"]([^'"]+)['"]""",
    )

    /**
     * Destination announced by an HTML page a shortener answers with:
     * meta refresh, a script setting location, og:url / canonical, or the only external link.
     * Null if nothing points away from the shortener.
     */
    fun targetFromHtml(html: String, pageUrl: String): String? {
        val doc = Jsoup.parse(html, pageUrl)
        val candidates = buildList {
            doc.select("meta[http-equiv~=(?i)refresh]").forEach { m ->
                META_REFRESH.find(m.attr("content"))?.groupValues?.get(1)?.let { add(it) }
            }
            doc.select("script").forEach { s ->
                JS_LOCATION.findAll(s.data()).forEach { add(it.groupValues[1].replace("\\/", "/")) }
            }
            doc.selectFirst("meta[property=og:url]")?.attr("content")?.let { add(it) }
            doc.selectFirst("link[rel=canonical]")?.attr("href")?.let { add(it) }
            val external = doc.select("a[href]").map { it.absUrl("href") }
                .filter { it.startsWith("http") && host(it) != host(pageUrl) }
                .distinct()
            if (external.size == 1) add(external.first())
        }
        return candidates
            .mapNotNull { absolute(it.trim(), pageUrl) }
            .firstOrNull { host(it) != host(pageUrl) && (it.startsWith("http://") || it.startsWith("https://")) }
    }

    private fun absolute(href: String, base: String): String? =
        runCatching { URI(base).resolve(href.replace(" ", "%20")).toString() }.getOrNull()

    /**
     * The real address behind [url]. Wrappers are decoded, shorteners followed one hop at a
     * time until the link leaves them. A normal URL is returned unchanged without network
     * access; on any network error the last address reached is returned (the page download
     * will follow ordinary redirects anyway).
     *
     * [isShort] is replaceable for tests.
     */
    fun resolve(url: String, userAgent: String, isShort: (String) -> Boolean = ::isShortener): String {
        var current = url.trim()
        val seen = mutableSetOf<String>()
        repeat(MAX_HOPS) {
            unwrap(current)?.let { current = it; return@repeat }
            if (!isShort(current) || !seen.add(current)) return current
            val next = runCatching { nextHop(current, userAgent) }.getOrNull() ?: return current
            if (next == current) return current
            current = next
        }
        return current
    }

    /** One step: the HTTP redirect target, or the destination announced by the page. */
    private fun nextHop(url: String, userAgent: String): String? {
        val res = Jsoup.connect(url)
            .userAgent(userAgent)
            .followRedirects(false)
            .ignoreHttpErrors(true)
            .ignoreContentType(true)
            .timeout(10_000)
            .maxBodySize(1024 * 1024)
            .execute()
        val status = res.statusCode()
        if (status in 300..399) {
            val location = res.header("Location")?.takeIf { it.isNotBlank() } ?: return null
            return absolute(location, url)
        }
        if (status in 200..299 && res.contentType().orEmpty().contains("html", ignoreCase = true)) {
            return targetFromHtml(res.body(), url)
        }
        return null
    }
}
