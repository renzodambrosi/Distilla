package io.github.renzodambrosi.distilla.core

/**
 * Prepares an article for text-to-speech.
 *
 * Android's TextToSpeech accepts at most getMaxSpeechInputLength() characters per call
 * (about 4000), so the text is split into paragraphs, and long paragraphs at sentence
 * boundaries. One chunk per paragraph also makes pause/resume land at a natural point.
 */
object SpeechText {

    private val SENTENCE_END = Regex("(?<=[.!?…;:])\\s+")

    fun chunks(title: String, text: String, maxLength: Int = 3900): List<String> {
        val paragraphs = buildList {
            if (title.isNotBlank()) add(title.trim())
            addAll(text.split(Regex("\n\\s*\n")))
        }
        return paragraphs
            .map { clean(it) }
            .filter { it.any(Char::isLetterOrDigit) }
            .flatMap { split(it, maxLength) }
    }

    /** Drops the visual markers of lists and quotes, which voices would read aloud. */
    private fun clean(p: String): String =
        p.lines().joinToString("\n") { line ->
            line.trim()
                .removePrefix(">").trim()
                .replace(Regex("^(•|\\d+\\.)\\s+"), "")
        }.replace("────────", "").trim()

    private fun split(p: String, max: Int): List<String> {
        if (p.length <= max) return listOf(p)
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in p.split(SENTENCE_END)) {
            if (current.isNotEmpty() && current.length + 1 + sentence.length > max) {
                out += current.toString()
                current.clear()
            }
            if (sentence.length > max) {
                // A single huge "sentence" with no punctuation: hard cut
                sentence.chunked(max).forEach { out += it }
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }
}
