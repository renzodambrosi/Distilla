package io.github.renzodambrosi.distilla.data

import android.content.Context
import io.github.renzodambrosi.distilla.core.Article
import io.github.renzodambrosi.distilla.core.ArticleExtractor
import io.github.renzodambrosi.distilla.core.BrowserHints
import io.github.renzodambrosi.distilla.core.HttpErrorException
import io.github.renzodambrosi.distilla.core.SiteRule
import io.github.renzodambrosi.distilla.core.UrlMatcher
import io.github.renzodambrosi.distilla.core.UrlResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

/**
 * Downloads a story and extracts its text, with the same steps everywhere
 * (reader screen, listening queue, Android Auto):
 *  1. shortened / wrapper links are resolved to the newspaper's address;
 *  2. the site rule decides User-Agent and browser engine;
 *  3. plain download, retried with the browser engine if the site blocks apps;
 *  4. text extraction with the rule that matches the final address.
 */
class ArticleLoader(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = Prefs(appContext)
    private val repo = RulesRepository(appContext)
    private val browser = BrowserFetcher(appContext)

    /** Progress, for screens that show it. */
    interface Stages {
        fun resolving(url: String) {}
        fun resolved(from: String, to: String) {}
        fun usingBrowser(url: String) {}
    }

    /** Real address behind a shortened / wrapper link; [url] itself otherwise (no network). */
    suspend fun resolve(url: String, stages: Stages? = null): String {
        if (!UrlResolver.isShortener(url) && UrlResolver.unwrap(url) == null) return url
        stages?.resolving(url)
        val target = withContext(Dispatchers.IO) { UrlResolver.resolve(url, prefs.userAgent) }
        if (target != url) stages?.resolved(url, target)
        return target
    }

    /**
     * Downloads the page. With [rule].useBrowser it uses the browser engine directly;
     * otherwise a plain download, retried with the browser engine if the site blocks it
     * (403/429/503) and the option is on. Returns the page and whether the browser was used.
     */
    suspend fun loadDocument(url: String, rule: SiteRule?, stages: Stages? = null): Pair<Document, Boolean> {
        // In the browser engine only a site's own User-Agent is applied: the WebView's
        // real one matches its TLS fingerprint, which is what gets past the checks.
        val siteUa = rule?.userAgent?.trim()?.takeIf { it.isNotEmpty() }
        if (rule?.useBrowser == true) {
            stages?.usingBrowser(url)
            return browser.fetch(url, siteUa) to true
        }
        val plain = try {
            withContext(Dispatchers.IO) {
                ArticleExtractor.fetch(url, ArticleExtractor.effectiveUserAgent(rule, prefs.userAgent))
            }
        } catch (e: HttpErrorException) {
            if (!e.looksBlocked || !prefs.browserFallback) throw e
            null
        }
        // Some protections answer 200 with the "checking your browser" page instead of 403
        if (plain != null && !(prefs.browserFallback && looksLikeChallenge(plain))) return plain to false
        stages?.usingBrowser(url)
        return browser.fetch(url, siteUa) to true
    }

    private suspend fun looksLikeChallenge(doc: Document): Boolean =
        withContext(Dispatchers.Default) { BrowserHints.isChallengePage(doc.outerHtml()) }

    /**
     * Text of a page obtained some other way, e.g. after the user completed the
     * anti-bot check on screen ([target]: the address that was requested).
     */
    suspend fun extract(doc: Document, target: String, withBrowser: Boolean = true): Article {
        val rules = withContext(Dispatchers.IO) { repo.load() }
        return withContext(Dispatchers.Default) {
            val rule = UrlMatcher.findRule(rules, doc.location()) ?: UrlMatcher.findRule(rules, target)
            ArticleExtractor.extract(doc, target, rule).copy(loadedWithBrowser = withBrowser)
        }
    }

    /** The whole pipeline: from any link to the extracted story. */
    suspend fun load(rawUrl: String, stages: Stages? = null): Article {
        val target = resolve(rawUrl, stages)
        val rules = withContext(Dispatchers.IO) { repo.load() }
        val requestRule = UrlMatcher.findRule(rules, target)
        val (doc, withBrowser) = loadDocument(target, requestRule, stages)
        return withContext(Dispatchers.Default) {
            val rule = UrlMatcher.findRule(rules, doc.location()) ?: requestRule
            ArticleExtractor.extract(doc, target, rule).copy(loadedWithBrowser = withBrowser)
        }
    }
}
