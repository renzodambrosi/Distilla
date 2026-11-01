package io.github.renzodambrosi.distilla.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.renzodambrosi.distilla.core.BrowserHints
import io.github.renzodambrosi.distilla.core.BrowserTimeoutException
import io.github.renzodambrosi.distilla.core.ChallengeRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Loads a page with the phone's browser engine (Android System WebView = Chrome).
 *
 * To the site this looks exactly like Chrome: same TLS fingerprint, same headers,
 * cookies, and JavaScript runs. It gets past anti-bot protections that block plain
 * app requests (e.g. Cloudflare answering 403) and also sees text added by JavaScript.
 * It is slower than a plain download, so it is used only when needed.
 *
 * Some checks never pass in a hidden WebView (they want a visible page, or a tap on
 * "I am human"): then [ChallengeRequiredException] tells the screen to show the page
 * to the user ([VerifyView]); the cookie the site sets afterwards is kept by the
 * WebView, so the following loads pass by themselves.
 */
class BrowserFetcher(private val context: Context) {

    companion object {
        /** Wait after a page finishes loading, so scripts can fill in the text. */
        private const val SETTLE_MS = 1_500L
        /** How often the page is looked at again while an anti-bot check is showing. */
        private const val POLL_MS = 2_000L
        /** A check still showing after this long won't pass alone: ask the user. */
        private const val CHALLENGE_WAIT_MS = 15_000L
        /** Total time allowed for a page without any check. */
        private const val TIMEOUT_MS = 35_000L

        /**
         * Same settings for the hidden engine and for the visible check: the site ties
         * its "you passed" cookie to the User-Agent, so both must send the same one.
         * [showImages] for the visible check (puzzles, logos); the hidden one skips them.
         */
        @SuppressLint("SetJavaScriptEnabled")
        fun configure(web: WebView, userAgent: String?, showImages: Boolean) {
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadsImagesAutomatically = showImages
                blockNetworkImage = !showImages
                userAgentString = userAgent?.trim()?.takeIf { it.isNotEmpty() }
                    ?: BrowserHints.cleanWebViewUserAgent(userAgentString)
            }
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        }

        /** The page's current HTML, decoded from the JavaScript string ("" if unavailable). */
        fun readHtml(view: WebView, onHtml: (String) -> Unit) {
            view.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                onHtml(runCatching { Json.parseToJsonElement(raw).jsonPrimitive.content }.getOrNull().orEmpty())
            }
        }

        /** Saves the cookies now (e.g. the anti-bot pass), not only when the app closes. */
        fun saveCookies() = CookieManager.getInstance().flush()
    }

    /** [userAgent] null or blank = the WebView's own, cleaned to look like the phone's Chrome. */
    suspend fun fetch(url: String, userAgent: String?): Document = withContext(Dispatchers.Main) {
        load(url, userAgent)
    }

    private suspend fun load(url: String, userAgent: String?): Document = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        val web = WebView(context.applicationContext)
        var done = false
        var loads = 0
        /** When the anti-bot page was first seen (0 = not seen). */
        var challengeSince = 0L

        fun cleanup() {
            handler.removeCallbacksAndMessages(null)
            web.stopLoading()
            web.destroy()
        }

        fun finish(result: Result<Document>) {
            if (done) return
            done = true
            cleanup()
            result.fold({ cont.resume(it) }, { cont.resumeWithException(it) })
        }

        // [load]: the page load this look belongs to; a newer load stops the older polling
        fun capture(view: WebView, pageUrl: String, load: Int) {
            if (done || load != loads) return
            readHtml(view) { html ->
                if (done || load != loads) return@readHtml
                if (html.isNotBlank() && !BrowserHints.isChallengePage(html)) {
                    saveCookies()
                    finish(Result.success(Jsoup.parse(html, view.url ?: pageUrl)))
                    return@readHtml
                }
                // Anti-bot check still running: look again shortly (it may pass without
                // a new page load), and give up on it after a while, asking the user
                val now = SystemClock.elapsedRealtime()
                if (challengeSince == 0L) challengeSince = now
                if (now - challengeSince >= CHALLENGE_WAIT_MS) {
                    finish(Result.failure(ChallengeRequiredException(url, userAgent?.trim()?.takeIf { it.isNotEmpty() })))
                } else {
                    handler.postDelayed({ capture(view, pageUrl, load) }, POLL_MS)
                }
            }
        }

        // An off-screen WebView still needs a size, or some pages never lay out their text
        web.layout(0, 0, 1080, 2400)
        configure(web, userAgent, showImages = false)

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, pageUrl: String) {
                val load = ++loads
                handler.postDelayed({ capture(view, pageUrl, load) }, SETTLE_MS)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) finish(Result.failure(IOException(error.description?.toString() ?: "WebView error")))
            }
        }

        // Overall limit: a check seen meanwhile means "ask the user", otherwise the page is just too slow
        handler.postDelayed({
            finish(Result.failure(
                if (challengeSince != 0L) ChallengeRequiredException(url, userAgent?.trim()?.takeIf { it.isNotEmpty() })
                else BrowserTimeoutException(url)
            ))
        }, TIMEOUT_MS)

        cont.invokeOnCancellation {
            // The user left: release the WebView on the main thread
            handler.post { if (!done) { done = true; cleanup() } }
        }
        web.loadUrl(url)
    }
}
