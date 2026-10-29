package io.github.renzodambrosi.distilla.playback

import android.content.Context
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.renzodambrosi.distilla.core.PlayQueue
import io.github.renzodambrosi.distilla.core.QueueSnapshot
import io.github.renzodambrosi.distilla.core.SpeechText
import io.github.renzodambrosi.distilla.data.ArticleLoader
import io.github.renzodambrosi.distilla.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A Media3 player that reads the listening queue aloud with text-to-speech.
 *
 * To Android it is an ordinary audio player: notification, lock screen, Bluetooth and
 * steering-wheel buttons, Android Auto. Each story is downloaded just before it is read
 * (the next one is prefetched), and split into paragraphs; the "position" in a story is
 * the paragraph: 1 second = 1 paragraph, so a seek bar jumps between paragraphs.
 */
@OptIn(UnstableApi::class)
class TtsPlayer(context: Context) : SimpleBasePlayer(Looper.getMainLooper()), TtsEngine.Listener {

    private val prefs = Prefs(context)
    private val loader = ArticleLoader(context)
    private val engine = TtsEngine(context, this)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ------------------------------------------------------------ player state

    private val items = mutableListOf<MediaItem>()
    private var current = -1
    private var playWhenReady = false
    private var playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
    private var playbackState = Player.STATE_IDLE
    /** Paused by a call / navigation prompt: resumes by itself when the audio comes back. */
    private var suppressed = false
    private var speed = prefs.speechRate

    // ------------------------------------------------------------ current story

    private var loadedUrl: String? = null
    private var chunks: List<String> = emptyList()
    private var chunk = 0
    private var language: String? = null
    private var speakingGen: Int? = null
    private var loadJob: Job? = null
    private var prefetchJob: Job? = null

    private val maxChunk = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceIn(500, 3900)

    init {
        restore()
        scope.launch {
            PlaybackBus.output.collect { out ->
                engine.setOutput(out)
                if (speakingGen != null) restartSpeech()
            }
        }
    }

    /** The queue as stored (main thread). */
    fun queueItems(): List<MediaItem> = items.toList()

    // ------------------------------------------------------------ SimpleBasePlayer

    override fun getState(): State {
        // Called by the superclass, possibly before this class's fields are set
        @Suppress("SENSELESS_COMPARISON")
        if (items == null) return State.Builder().build()

        val playlist = items.mapIndexed { i, item ->
            val known = i == current && loadedUrl == item.mediaId && chunks.isNotEmpty()
            MediaItemData.Builder(item.mediaId)
                .setMediaItem(item)
                .setIsSeekable(true)
                .setDurationUs(if (known) chunks.size * 1_000_000L else C.TIME_UNSET)
                .build()
        }
        val state = if (items.isEmpty() && playbackState != Player.STATE_IDLE) Player.STATE_ENDED else playbackState
        val builder = State.Builder()
            .setAvailableCommands(COMMANDS)
            .setPlayWhenReady(playWhenReady, playWhenReadyReason)
            .setPlaybackState(state)
            .setPlaybackSuppressionReason(
                if (suppressed) Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS
                else Player.PLAYBACK_SUPPRESSION_REASON_NONE
            )
            .setPlaybackParameters(PlaybackParameters(speed))
            // Media3 allows "loading" only outside IDLE / ENDED
            .setIsLoading(loadJob?.isActive == true && state != Player.STATE_IDLE && state != Player.STATE_ENDED)
            .setPlaylist(playlist)
        if (items.isNotEmpty()) {
            builder.setCurrentMediaItemIndex(current.coerceIn(0, items.lastIndex))
            // Constant, not extrapolated with time: the "position" is the paragraph (1 s = 1 paragraph)
            builder.setContentPositionMs(PositionSupplier.getConstant(chunk * 1000L))
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST
        suppressed = false
        if (playWhenReady) {
            if (items.isEmpty()) {
                this.playWhenReady = false
            } else {
                if (playbackState == Player.STATE_IDLE) playbackState = Player.STATE_READY
                if (playbackState == Player.STATE_ENDED) {   // play again after the end: last story from the top
                    chunk = 0
                    playbackState = Player.STATE_READY
                }
                startCurrent()
            }
        } else {
            stopSpeech()
        }
        persist()
        return done()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        if (items.isNotEmpty() && playbackState == Player.STATE_IDLE) playbackState = Player.STATE_READY
        return done()
    }

    override fun handleStop(): ListenableFuture<*> {
        stopSpeech()
        loadJob?.cancel()
        playbackState = Player.STATE_IDLE
        persist()
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> {
        persist()
        engine.shutdown()
        scope.cancel()
        return done()
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        stopSpeech(keepFocus = true)
        items.clear()
        items.addAll(PlayQueue.distinctNew(emptyList(), mediaItems) { it.mediaId })
        current = when {
            items.isEmpty() -> -1
            startIndex == C.INDEX_UNSET -> 0
            else -> startIndex.coerceIn(0, items.lastIndex)
        }
        resetStory(chunkOf(startPositionMs))
        playbackState = when {
            items.isEmpty() -> Player.STATE_ENDED
            playbackState == Player.STATE_ENDED -> Player.STATE_READY
            else -> playbackState
        }
        startCurrent()
        persist()
        return done()
    }

    override fun handleAddMediaItems(index: Int, mediaItems: List<MediaItem>): ListenableFuture<*> {
        val new = PlayQueue.distinctNew(items.map { it.mediaId }, mediaItems) { it.mediaId }
        if (new.isEmpty()) return done()
        val at = index.coerceIn(0, items.size)
        val wasEmpty = items.isEmpty()
        items.addAll(at, new)
        current = PlayQueue.currentAfterAdd(current, at, new.size)
        when {
            wasEmpty -> {
                resetStory(0)
                if (playbackState == Player.STATE_ENDED) playbackState = Player.STATE_READY
            }
            // Everything had been read: the first new story is next, waiting for "play"
            playbackState == Player.STATE_ENDED -> {
                current = at
                resetStory(0)
                playbackState = Player.STATE_READY
            }
        }
        persist()
        prefetchNext()
        return done()
    }

    override fun handleRemoveMediaItems(fromIndex: Int, toIndex: Int): ListenableFuture<*> {
        val removingCurrent = current in fromIndex until toIndex
        val sizeBefore = items.size
        repeat(toIndex - fromIndex) { items.removeAt(fromIndex) }
        current = PlayQueue.currentAfterRemove(current, fromIndex, toIndex, sizeBefore)
        if (removingCurrent) {
            stopSpeech(keepFocus = true)
            resetStory(0)
            if (current < 0) {
                stopSpeech()
                playWhenReady = false
                playbackState = Player.STATE_ENDED
            } else {
                startCurrent()
            }
        }
        persist()
        return done()
    }

    override fun handleMoveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int): ListenableFuture<*> {
        val moved = PlayQueue.move(items, fromIndex, toIndex, newIndex)
        current = PlayQueue.currentAfterMove(current, fromIndex, toIndex, newIndex, items.size)
        items.clear()
        items.addAll(moved)
        persist()
        prefetchNext()
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        if (mediaItemIndex !in items.indices) return done()
        stopSpeech(keepFocus = true)
        if (mediaItemIndex != current) {
            current = mediaItemIndex
            resetStory(chunkOf(positionMs))
        } else {
            chunk = chunkOf(positionMs).let { if (chunks.isEmpty()) it else it.coerceIn(0, chunks.lastIndex) }
        }
        if (playbackState == Player.STATE_ENDED) playbackState = Player.STATE_READY
        startCurrent()
        persist()
        return done()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed.coerceIn(0.5f, 3.0f)
        prefs.speechRate = speed
        if (speakingGen != null) restartSpeech()
        return done()
    }

    // ------------------------------------------------------------ reading

    private fun chunkOf(positionMs: Long) = if (positionMs == C.TIME_UNSET || positionMs < 0) 0 else (positionMs / 1000).toInt()

    /** Forgets the text of the current story (it stays in the cache) and starts it at [fromChunk]. */
    private fun resetStory(fromChunk: Int) {
        loadJob?.cancel()
        loadedUrl = null
        chunks = emptyList()
        chunk = fromChunk
        language = null
        publishProgress()
    }

    private fun stopSpeech(keepFocus: Boolean = false) {
        speakingGen = null
        engine.stop(keepFocus)
    }

    /** Reads the current story, downloading it first if needed. Does nothing while paused. */
    private fun startCurrent() {
        if (!playWhenReady || current !in items.indices || playbackState == Player.STATE_IDLE) return
        val item = items[current]
        val url = item.mediaId
        if (loadedUrl == url && chunks.isNotEmpty()) {
            speakNow()
            return
        }
        loadJob?.cancel()
        playbackState = Player.STATE_BUFFERING
        publishProgress(loading = true)
        invalidateState()
        // Dispatchers.Main (not "immediate"): the body never runs inside the caller, so
        // a Media3 handler is never re-entered and loadJob always refers to this job.
        loadJob = scope.launch(Dispatchers.Main) {
            try {
                val article = PlaybackBus.cached(url) ?: loader.load(url).also { PlaybackBus.remember(it) }
                if (items.getOrNull(current)?.mediaId != url) return@launch   // the queue changed meanwhile
                // Now the real title is known: show it in the notification and in Android Auto
                if (QueueMedia.titleOf(item).isBlank() && article.title.isNotBlank()) {
                    items[current] = QueueMedia.item(url, article.title)
                }
                chunks = SpeechText.chunks(article.title, article.text, maxChunk)
                if (chunks.isEmpty()) {
                    skipFailed(article.title.ifBlank { QueueMedia.toQueueItem(item).host })
                    return@launch
                }
                loadedUrl = url
                chunk = chunk.coerceIn(0, chunks.lastIndex)
                engine.whenReady { ok ->
                    if (loadedUrl != url) return@whenReady   // moved on to another story meanwhile
                    if (!ok) {
                        playWhenReady = false
                        playbackState = Player.STATE_READY
                        publishProgress()
                        invalidateState()
                        return@whenReady
                    }
                    language = engine.applyLanguage(article.title, article.text, article.language)
                    playbackState = Player.STATE_READY
                    speakNow()
                }
                prefetchNext()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                skipFailed(QueueMedia.titleOf(item).ifBlank { QueueMedia.toQueueItem(item).host })
            }
        }
    }

    private fun speakNow() {
        if (!playWhenReady || chunks.isEmpty()) return
        val gen = engine.speak(chunks, chunk, speed)
        if (gen == null) {
            // Audio busy (e.g. a call): wait; onFocusGained() starts reading
            suppressed = true
        } else {
            speakingGen = gen
            suppressed = false
        }
        playbackState = Player.STATE_READY
        publishProgress()
        invalidateState()
    }

    private fun restartSpeech() {
        stopSpeech(keepFocus = true)
        speakNow()
    }

    /** A story couldn't be read: say so and go on with the next one. */
    private fun skipFailed(title: String) {
        PlaybackBus.post(PlaybackBus.Notice(PlaybackBus.NoticeKind.LOAD_FAILED, title = title))
        speakingGen = null
        nextStoryOrEnd()
    }

    private fun nextStoryOrEnd() {
        if (current < items.lastIndex) {
            current++
            resetStory(0)
            startCurrent()
        } else {
            stopSpeech()
            playWhenReady = false
            playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM
            playbackState = Player.STATE_ENDED
            publishProgress()
            invalidateState()
        }
        persist()
    }

    /** Downloads the next story in the background, so it starts without waiting. */
    private fun prefetchNext() {
        val next = items.getOrNull(current + 1)?.mediaId ?: return
        if (PlaybackBus.cached(next) != null || prefetchJob?.isActive == true) return
        prefetchJob = scope.launch(Dispatchers.Main) {
            runCatching { loader.load(next) }.getOrNull()?.let { PlaybackBus.remember(it) }
        }
    }

    private fun publishProgress(loading: Boolean = false) {
        PlaybackBus.progress.value = PlaybackBus.Progress(chunk, chunks.size, language, loading)
    }

    // ------------------------------------------------------------ TtsEngine.Listener

    override fun onChunkStart(generation: Int, index: Int) {
        if (generation != speakingGen) return
        chunk = index
        publishProgress()
        persist()
        invalidateState()
    }

    override fun onChunkDone(generation: Int, index: Int) {
        if (generation != speakingGen || index < chunks.lastIndex) return
        speakingGen = null
        nextStoryOrEnd()
    }

    override fun onSpeechError(generation: Int) {
        if (generation != speakingGen) return
        PlaybackBus.post(PlaybackBus.Notice(PlaybackBus.NoticeKind.TTS_ERROR))
        stopSpeech()
        playWhenReady = false
        invalidateState()
    }

    override fun onFocusLostTransient() {
        if (speakingGen == null) return
        stopSpeech(keepFocus = true)
        suppressed = true
        invalidateState()
    }

    override fun onFocusLost() {
        stopSpeech()
        suppressed = false
        if (playWhenReady) {
            playWhenReady = false
            playWhenReadyReason = Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS
        }
        persist()
        invalidateState()
    }

    override fun onFocusGained() {
        if (!suppressed) return
        suppressed = false
        if (playWhenReady && loadedUrl != null) speakNow() else invalidateState()
    }

    // ------------------------------------------------------------ persistence

    private fun persist() {
        prefs.queueJson = PlayQueue.toJson(
            QueueSnapshot(items.map(QueueMedia::toQueueItem), if (items.isEmpty()) -1 else current, chunk)
        )
    }

    private fun restore() {
        val s = PlayQueue.fromJson(prefs.queueJson)
        items.addAll(s.items.map(QueueMedia::item))
        current = s.current
        chunk = s.chunk
        playbackState = Player.STATE_IDLE
    }

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private companion object {
        val COMMANDS: Player.Commands = Player.Commands.Builder().addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_SPEED_AND_PITCH,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_RELEASE,
        ).build()
    }
}
