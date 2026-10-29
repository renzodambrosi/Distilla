package io.github.renzodambrosi.distilla.core

import java.net.URL

// Confronto URL con wildcard.
//
// Regole del pattern:
//  - contiene "://"  → confrontato con l'URL completo        es. https://www.ansa.it/sito/notizie/*
//  - contiene "/"    → confrontato con l'URL senza schema     es. www.ilpost.it/20*
//  - nessuna "/"     → confrontato solo con l'host            es. *.corriere.it  (vale anche per corriere.it)
//
// "*" = qualsiasi sequenza di caratteri, "?" = un carattere. Maiuscole/minuscole ignorate.
// Se più regole corrispondono vince la più specifica (più caratteri non jolly).
object UrlMatcher {

    private val SPECIAL = ".[]{}()+^$|\\"
    private val cache = HashMap<String, Regex>()

    fun toRegex(pattern: String): Regex = synchronized(cache) {
        cache.getOrPut(pattern) {
            val sb = StringBuilder("^")
            for (c in pattern) {
                when (c) {
                    '*' -> sb.append(".*")
                    '?' -> sb.append('.')
                    in SPECIAL -> sb.append('\\').append(c)
                    else -> sb.append(c)
                }
            }
            sb.append('$')
            Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }
    }

    fun matches(pattern: String, url: String): Boolean {
        val p = pattern.trim()
        if (p.isEmpty()) return false
        val u = url.trim()
        return when {
            "://" in p -> toRegex(p).matches(u)
            "/" in p -> toRegex(p).matches(u.substringAfter("://"))
            else -> {
                val host = hostOf(u) ?: return false
                toRegex(p).matches(host) ||
                    (p.startsWith("*.") && host.equals(p.removePrefix("*."), ignoreCase = true))
            }
        }
    }

    fun specificity(pattern: String): Int = pattern.count { it != '*' && it != '?' }

    fun findRule(rules: List<SiteRule>, url: String): SiteRule? =
        rules.filter { matches(it.pattern, url) }.maxByOrNull { specificity(it.pattern) }

    @Suppress("DEPRECATION")
    fun hostOf(url: String): String? =
        (runCatching { java.net.URI(url).host }.getOrNull() ?: runCatching { URL(url).host }.getOrNull())
            ?.lowercase()?.takeIf { it.isNotEmpty() }

    private val SECOND_LEVEL = setOf("co", "com", "org", "net", "gov", "ac", "edu", "gv", "or", "ne")

    /** Propone un pattern per il dominio dell'URL: https://www.ansa.it/x → *.ansa.it */
    fun suggestPattern(url: String): String {
        val host = hostOf(url) ?: return ""
        val labels = host.split('.')
        if (labels.size < 2) return host
        val take = if (labels.size >= 3 && labels[labels.size - 2] in SECOND_LEVEL) 3 else 2
        return "*." + labels.takeLast(take).joinToString(".")
    }
}

/** Trova il primo link http(s) dentro un testo condiviso (spesso è "Titolo https://..."). */
object UrlExtractor {
    private val URL_RE = Regex("""https?://[^\s<>"'«»]+""", RegexOption.IGNORE_CASE)

    fun find(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val m = URL_RE.find(text)?.value ?: return null
        return m.trimEnd('.', ',', ';', ':', ')', ']', '!', '?')
    }
}
