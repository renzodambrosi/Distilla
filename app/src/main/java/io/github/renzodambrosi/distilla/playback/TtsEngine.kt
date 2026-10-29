package io.github.renzodambrosi.distilla.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.renzodambrosi.distilla.core.LanguageDetector
import java.util.Locale

/**
 * The system text-to-speech engine (engine and voices chosen in Android settings),
 * with audio focus. It only speaks what [TtsPlayer] tells it to; all callbacks
 * arrive on the main thread.
 *
 * Audio: the speech is declared with explicit AudioAttributes and plays only while holding
 * audio focus. Without both, Android Auto (and some car / Bluetooth setups) mute it.
 */
class TtsEngine(context: Context, private val listener: Listener) {

    interface Listener {
        /** Paragraph [index] of the batch [generation] started / finished. */
        fun onChunkStart(generation: Int, index: Int)
        fun onChunkDone(generation: Int, index: Int)
        fun onSpeechError(generation: Int)
        /** A call, an assistant or a navigation prompt took the audio for a while. */
        fun onFocusLostTransient()
        /** Another app took the audio for good (e.g. music started). */
        fun onFocusLost()
        /** The audio is ours again (also after a delayed grant). */
        fun onFocusGained()
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pendingWhenReady = mutableListOf<(Boolean) -> Unit>()

    private var generation = 0
    var output = PlaybackBus.Output.MEDIA
        private set

    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false

    /** Runs [block] once the engine is initialised; false if text-to-speech isn't available. */
    fun whenReady(block: (Boolean) -> Unit) {
        if (ready) { block(true); return }
        pendingWhenReady += block
        if (tts == null) tts = TextToSpeech(appContext) { status -> main.post { onInit(status) } }
    }

    private fun onInit(status: Int) {
        val engine = tts ?: return
        val ok = status == TextToSpeech.SUCCESS
        if (ok) {
            ready = true
            engine.setAudioAttributes(audioAttributes())
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) { main.post { parse(id)?.let { (g, i) -> listener.onChunkStart(g, i) } } }
                override fun onDone(id: String) { main.post { parse(id)?.let { (g, i) -> listener.onChunkDone(g, i) } } }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String) { main.post { parse(id)?.let { (g, _) -> listener.onSpeechError(g) } } }
                override fun onError(id: String, errorCode: Int) { main.post { parse(id)?.let { (g, _) -> listener.onSpeechError(g) } } }
            })
        } else {
            engine.shutdown()
            tts = null
            PlaybackBus.post(PlaybackBus.Notice(PlaybackBus.NoticeKind.TTS_UNAVAILABLE))
        }
        val callbacks = pendingWhenReady.toList()
        pendingWhenReady.clear()
        callbacks.forEach { it(ok) }
    }

    private fun parse(id: String): Pair<Int, Int>? {
        val parts = id.split(':')
        if (parts.size != 2) return null
        return (parts[0].toIntOrNull() ?: return null) to (parts[1].toIntOrNull() ?: return null)
    }

    // ------------------------------------------------------------ speaking

    /**
     * Speaks [chunks] from [from] on. Returns the batch number used in the callbacks,
     * or null if the audio focus isn't available now (a delayed grant calls onFocusGained).
     */
    fun speak(chunks: List<String>, from: Int, rate: Float): Int? {
        val engine = tts ?: return null
        if (!requestFocus()) return null
        engine.stop()
        engine.setAudioAttributes(audioAttributes())
        engine.setSpeechRate(rate)
        val gen = ++generation
        for (i in from until chunks.size) {
            val mode = if (i == from) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(chunks[i], mode, null, "$gen:$i")
        }
        return gen
    }

    /** Stops speaking. With [keepFocus] the focus request stays, to be told when to go on. */
    fun stop(keepFocus: Boolean = false) {
        generation++
        tts?.stop()
        if (!keepFocus) abandonFocus()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    fun setOutput(value: PlaybackBus.Output) {
        if (value == output) return
        output = value
        abandonFocus()   // the next speak() asks again with the new channel
        tts?.setAudioAttributes(audioAttributes())
    }

    // ------------------------------------------------------------ language

    private fun displayName(tag: String): String =
        Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault()).ifBlank { tag }

    private fun base(tag: String) = tag.substringBefore('-').lowercase()

    /**
     * Voice for a story: the language it is written in, else the page's, else the phone's,
     * the first with a voice installed. Returns the language name, or null for the default voice.
     */
    fun applyLanguage(title: String, text: String, pageLanguage: String?): String? {
        val engine = tts ?: return null
        val detected = LanguageDetector.detect(title + "\n\n" + text)
        val candidates = LanguageDetector.voiceCandidates(detected, pageLanguage, Locale.getDefault().toLanguageTag())
        val wanted = candidates.firstOrNull() ?: return null
        for (tag in candidates) {
            val locale = runCatching { Locale.forLanguageTag(tag) }.getOrNull() ?: continue
            if (locale.language.isEmpty()) continue
            if (engine.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) continue
            if (engine.setLanguage(locale) < TextToSpeech.LANG_AVAILABLE) continue
            val name = displayName(tag)
            // Same language with another region ("pt-BR" → "pt") is not worth a warning
            if (base(tag) != base(wanted)) {
                PlaybackBus.post(PlaybackBus.Notice(PlaybackBus.NoticeKind.LANGUAGE_MISSING, displayName(wanted), name))
            }
            return name
        }
        PlaybackBus.post(PlaybackBus.Notice(PlaybackBus.NoticeKind.LANGUAGE_MISSING, displayName(wanted), null))
        return null
    }

    // ------------------------------------------------------------ audio focus

    private fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .setUsage(
            if (output == PlaybackBus.Output.GUIDANCE) AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            else AudioAttributes.USAGE_MEDIA
        )
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> { hasFocus = true; listener.onFocusGained() }
            // Speech must not be ducked under another voice: pause instead (see setWillPauseWhenDucked)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { hasFocus = false; listener.onFocusLostTransient() }
            AudioManager.AUDIOFOCUS_LOSS -> { abandonFocus(); listener.onFocusLost() }
        }
    }

    /** Asks Android for the right to play. False if refused or granted only later. */
    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        val request = AudioFocusRequest.Builder(
            // Media: other players pause. Guidance: they only lower their volume while we speak.
            if (output == PlaybackBus.Output.GUIDANCE) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            else AudioManager.AUDIOFOCUS_GAIN
        )
            .setAudioAttributes(audioAttributes())
            .setAcceptsDelayedFocusGain(true)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
        focusRequest = request
        hasFocus = audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        hasFocus = false
    }
}
