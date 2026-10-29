package io.github.renzodambrosi.distilla.playback

import io.github.renzodambrosi.distilla.core.Article
import io.github.renzodambrosi.distilla.core.PlayQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Extra information between the playback service and the screens, in the same process.
 * Play / pause / queue changes go through Media3 (MediaController ↔ MediaSession);
 * this carries what the standard player model has no place for.
 */
object PlaybackBus {

    data class Progress(
        /** Paragraph being read (0-based) and total paragraphs of the current story. */
        val chunk: Int = 0,
        val total: Int = 0,
        /** Language of the voice, in the user's language ("tedesco"). */
        val language: String? = null,
        /** Downloading the current story. */
        val loading: Boolean = false,
    )

    enum class NoticeKind { TTS_UNAVAILABLE, LANGUAGE_MISSING, TTS_ERROR, LOAD_FAILED }

    /**
     * Something to tell the user. [wanted] / [used]: language names for LANGUAGE_MISSING;
     * [title]: the story that couldn't be loaded for LOAD_FAILED.
     */
    data class Notice(
        val kind: NoticeKind,
        val wanted: String? = null,
        val used: String? = null,
        val title: String? = null,
    )

    enum class Output { MEDIA, GUIDANCE }

    val progress = MutableStateFlow(Progress())

    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice
    fun post(n: Notice) { _notice.value = n }
    fun consumeNotice() { _notice.value = null }

    /** Audio channel chosen in Settings; the player follows it. */
    val output = MutableStateFlow(Output.MEDIA)

    // ------------------------------------------------------------ article cache

    private const val CACHE_SIZE = 12
    private val cache = object : LinkedHashMap<String, Article>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Article>?) = size > CACHE_SIZE
    }

    private fun key(url: String) = url.trim().substringBefore('#').trimEnd('/').lowercase()

    /** A story already downloaded (by the reader or by the player): no second download. */
    fun cached(url: String): Article? = synchronized(cache) { cache[key(url)] }

    fun remember(article: Article) = synchronized(cache) {
        cache[key(article.url)] = article
        if (!PlayQueue.sameUrl(article.requestedUrl, article.url)) cache[key(article.requestedUrl)] = article
    }
}
