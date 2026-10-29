package io.github.renzodambrosi.distilla.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.renzodambrosi.distilla.core.BrowserHints
import io.github.renzodambrosi.distilla.core.BrowserTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 */
class BrowserFetcher(private val context: Context) {

    private companion object {
        /** Wait after a page finishes loading, so scripts can fill in the text. */
        const val SETTLE_MS = 1_500L
        /** Total time allowed, including an anti-bot check that reloads the page. */
        const val TIMEOUT_MS = 35_000L
    }

    /** [userAgent] null or blank = the WebView's own, cleaned to look like the phone's Chrome. */
    suspend fun fetch(url: String, userAgent: String?): Document = withContext(Dispatchers.Main) {
        withTimeoutOrNull(TIMEOUT_MS) { load(url, userAgent) } ?: throw BrowserTimeoutException(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun load(url: String, userAgent: String?): Document = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        val web = WebView(context.applicationContext)
        var done = false
        var loads = 0

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

        // An off-screen WebView still needs a size, or some pages never lay out their text
        web.layout(0, 0, 1080, 2400)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadsImagesAutomatically = false
            blockNetworkImage = true
            userAgentString = userAgent?.trim()?.takeIf { it.isNotEmpty() }
                ?: BrowserHints.cleanWebViewUserAgent(userAgentString)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, pageUrl: String) {
                val load = ++loads
                handler.postDelayed({ if (load == loads && !done) capture(view, pageUrl) }, SETTLE_MS)
            }

            private fun capture(view: WebView, pageUrl: String) {
                view.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                    if (done) return@evaluateJavascript
                    // The result is a JSON string literal: decode it to plain HTML
                    val html = runCatching { Json.parseToJsonElement(raw).jsonPrimitive.content }.getOrNull().orEmpty()
                    // Anti-bot check still running: wait for the page it loads when it passes
                    if (html.isBlank() || BrowserHints.isChallengePage(html)) return@evaluateJavascript
                    finish(Result.success(Jsoup.parse(html, view.url ?: pageUrl)))
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) finish(Result.failure(IOException(error.description?.toString() ?: "WebView error")))
            }
        }

        cont.invokeOnCancellation {
            // Timeout or the user left: release the WebView on the main thread
            handler.post { if (!done) { done = true; cleanup() } }
        }
        web.loadUrl(url)
    }
}
