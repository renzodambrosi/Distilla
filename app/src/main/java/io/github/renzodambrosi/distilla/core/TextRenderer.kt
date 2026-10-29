package io.github.renzodambrosi.distilla.core

import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Trasforma HTML in testo semplice "alla lynx": paragrafi separati da una riga vuota,
 * elenchi puntati/numerati, citazioni con "> ", niente link, immagini o script.
 * Il risultato è pensato per essere letto e incollato in chat (WhatsApp, Telegram, mail).
 */
object TextRenderer {

    private val SKIP = setOf(
        "script", "style", "noscript", "iframe", "svg", "canvas", "video", "audio", "button",
        "input", "select", "textarea", "template", "object", "embed", "img", "picture", "head",
    )
    private val DOUBLE = setOf(
        "p", "h1", "h2", "h3", "h4", "h5", "h6", "table", "dl", "figure", "address",
    )
    private val SINGLE = setOf(
        "div", "section", "article", "main", "header", "footer", "tr", "dd", "dt", "figcaption",
        "fieldset", "form", "center", "details", "summary", "caption",
    )

    private class ListState(val ordered: Boolean) { var counter = 0 }

    fun render(elements: List<Element>): String {
        val sb = StringBuilder()
        for (e in elements) {
            walk(e, sb, emptyList())
            breakLines(sb, 2)
        }
        return normalize(sb.toString())
    }

    private fun children(node: Element, sb: StringBuilder, lists: List<ListState>) {
        for (child in node.childNodes()) walk(child, sb, lists)
    }

    private fun walk(node: Node, sb: StringBuilder, lists: List<ListState>) {
        when (node) {
            is TextNode -> appendText(sb, node.text())
            is Element -> {
                val tag = node.normalName()
                if (tag in SKIP) return
                when (tag) {
                    "br" -> { trimTrailingSpaces(sb); sb.append('\n') }
                    "hr" -> { breakLines(sb, 2); sb.append("────────"); breakLines(sb, 2) }
                    "ul", "ol" -> {
                        val gap = if (lists.isEmpty()) 2 else 1
                        breakLines(sb, gap)
                        children(node, sb, lists + ListState(tag == "ol"))
                        breakLines(sb, gap)
                    }
                    "li" -> renderListItem(node, sb, lists)
                    "blockquote" -> {
                        breakLines(sb, 2)
                        val inner = StringBuilder()
                        children(node, inner, lists)
                        val t = normalize(inner.toString())
                        if (t.isNotEmpty()) sb.append(t.lines().joinToString("\n") { if (it.isBlank()) ">" else "> $it" })
                        breakLines(sb, 2)
                    }
                    "pre" -> { breakLines(sb, 2); sb.append(node.wholeText().trimEnd()); breakLines(sb, 2) }
                    "td", "th" -> {
                        children(node, sb, lists)
                        trimTrailingSpaces(sb)
                        if (sb.isNotEmpty() && sb.last() != '\n') sb.append("   ")
                    }
                    in DOUBLE -> { breakLines(sb, 2); children(node, sb, lists); breakLines(sb, 2) }
                    in SINGLE -> { breakLines(sb, 1); children(node, sb, lists); breakLines(sb, 1) }
                    else -> children(node, sb, lists)
                }
            }
        }
    }

    private fun renderListItem(node: Element, sb: StringBuilder, lists: List<ListState>) {
        val inner = StringBuilder()
        children(node, inner, lists)
        val text = normalize(inner.toString())
        if (text.isEmpty()) return
        val state = lists.lastOrNull()
        val bullet = if (state?.ordered == true) "${++state.counter}. " else "• "
        val pad = " ".repeat(bullet.length)
        breakLines(sb, 1)
        text.lines().forEachIndexed { i, line ->
            if (i > 0) sb.append('\n')
            if (line.isNotBlank()) sb.append(if (i == 0) bullet else pad).append(line)
        }
        breakLines(sb, 1)
    }

    private fun appendText(sb: StringBuilder, raw: String) {
        var s = raw.replace(' ', ' ')
        if (s.isEmpty()) return
        if (sb.isEmpty() || sb.last() == '\n' || sb.last() == ' ') s = s.trimStart()
        sb.append(s)
    }

    private fun trimTrailingSpaces(sb: StringBuilder) {
        while (sb.isNotEmpty() && (sb.last() == ' ' || sb.last() == '\t')) sb.setLength(sb.length - 1)
    }

    /** Garantisce che il testo termini con esattamente [n] a capo (se non è vuoto). */
    private fun breakLines(sb: StringBuilder, n: Int) {
        trimTrailingSpaces(sb)
        if (sb.isEmpty()) return
        var existing = 0
        var i = sb.length - 1
        while (i >= 0 && sb[i] == '\n') { existing++; i-- }
        repeat(n - existing) { sb.append('\n') }
    }

    fun normalize(s: String): String =
        s.lines().joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim('\n', ' ')

    /** Testo già semplice (es. articleBody del JSON-LD): pulisce spazi e righe vuote. */
    fun normalizePlain(s: String): String =
        s.replace("\r\n", "\n").replace(' ', ' ')
            .lines().joinToString("\n") { it.trim().replace(Regex(" {2,}"), " ") }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}
