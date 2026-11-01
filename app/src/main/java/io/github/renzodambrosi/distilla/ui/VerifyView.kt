package io.github.renzodambrosi.distilla.ui

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.renzodambrosi.distilla.R
import io.github.renzodambrosi.distilla.core.BrowserHints
import io.github.renzodambrosi.distilla.data.BrowserFetcher

/**
 * The site's anti-bot check, shown to the user: often it passes by itself in a few
 * seconds once visible, otherwise it asks for a tap ("I am human"). As soon as the real
 * page is there its HTML goes to [onPassed]; the site's cookie stays in the WebView,
 * so the next stories of the same site load in the background again.
 */
@Composable
fun VerifyView(url: String, host: String, userAgent: String?, onPassed: (html: String, pageUrl: String) -> Unit) {
    val passed by rememberUpdatedState(onPassed)
    var loading by remember(url) { mutableStateOf(true) }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    stringResource(R.string.verify_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    stringResource(R.string.verify_hint, host),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    BrowserFetcher.configure(this, userAgent, showImages = true)
                    var done = false
                    var loads = 0

                    // Looks at the page now and every 2 s: some checks pass without loading a new page
                    fun check(load: Int) {
                        if (done || load != loads || tag === RELEASED) return
                        BrowserFetcher.readHtml(this) { html ->
                            if (done || load != loads || tag === RELEASED) return@readHtml
                            if (html.isNotBlank() && !BrowserHints.isChallengePage(html)) {
                                done = true
                                BrowserFetcher.saveCookies()
                                passed(html, this.url ?: url)
                            } else {
                                postDelayed({ check(load) }, 2_000)
                            }
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, pageUrl: String, favicon: android.graphics.Bitmap?) {
                            loading = true
                        }

                        override fun onPageFinished(view: WebView, pageUrl: String) {
                            loading = false
                            val load = ++loads
                            // Give scripts a moment to fill in the text, as the hidden engine does
                            postDelayed({ check(load) }, 1_500)
                        }
                    }
                    loadUrl(url)
                }
            },
            onRelease = { web ->
                // Pending checks see the mark and stop (the view's handler is the window's: not cleared)
                web.tag = RELEASED
                web.stopLoading()
                web.destroy()
            },
        )
    }
}

/** Marks a verification WebView already released by Compose. */
private val RELEASED = Any()
