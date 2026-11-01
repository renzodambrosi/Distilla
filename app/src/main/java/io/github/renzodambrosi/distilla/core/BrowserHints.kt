package io.github.renzodambrosi.distilla.core

import java.io.IOException

/**
 * The site answered with an HTTP error. [server] is the "Server" response header
 * (e.g. "cloudflare"), useful to tell an anti-bot block from a missing page.
 */
class HttpErrorException(val status: Int, val server: String?, val url: String) :
    IOException("HTTP $status from ${server ?: "?"} for $url") {

    /** Typical answers of anti-bot protections: worth retrying with the browser engine. */
    val looksBlocked: Boolean get() = status in BrowserHints.BLOCKED_STATUS
}

/** The browser engine didn't get the page in time (no anti-bot check seen: just a slow page). */
class BrowserTimeoutException(val url: String) : IOException("browser engine timed out for $url")

/**
 * The site shows an anti-bot check that doesn't pass by itself in the hidden browser engine:
 * it must be shown to the user, who completes it once (the site's cookie is then kept).
 * [userAgent]: the one the hidden engine used (null = the engine's own); the visible
 * check must use the same one, because the site ties its cookie to it.
 */
class ChallengeRequiredException(val url: String, val userAgent: String?) :
    IOException("anti-bot check needs the user for $url")

/** Helpers for loading pages with the system browser engine (Android WebView). */
object BrowserHints {

    /** 403 Forbidden, 429 Too Many Requests, 503 (Cloudflare / DDoS-Guard challenges). */
    val BLOCKED_STATUS = setOf(403, 429, 503)

    private val CHALLENGE_MARKERS = listOf(
        "cf-chl", "cf_chl_opt", "challenge-platform", "/cdn-cgi/challenge",   // Cloudflare
        "<title>just a moment", "<title>un momento", "<title>attention required",
        "checking your browser", "verifica che tu sia un essere umano", "verify you are human",
        "ddos-guard",                                                           // DDoS-Guard
        "captcha-delivery.com",                                                 // DataDome
        "_incapsula_resource",                                                  // Imperva
    )

    /**
     * True if the HTML is an anti-bot interstitial rather than the real page:
     * the browser engine should wait for the check to pass and the next page to load.
     */
    fun isChallengePage(html: String): Boolean {
        val head = html.take(40_000).lowercase()
        return CHALLENGE_MARKERS.any { it in head }
    }

    /**
     * The WebView's own User-Agent says "; wv" and "Version/4.0", which some sites use
     * to spot in-app browsers. Without them it is the same as the phone's Chrome.
     */
    fun cleanWebViewUserAgent(ua: String): String =
        ua.replace("; wv)", ")")
            .replace(Regex("""\s+Version/\d+(\.\d+)*"""), "")
            .trim()
}
