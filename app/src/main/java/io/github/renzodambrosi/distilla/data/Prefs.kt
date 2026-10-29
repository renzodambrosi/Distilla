package io.github.renzodambrosi.distilla.data

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    companion object {
        const val DEFAULT_REMOTE_URL =
            "https://raw.githubusercontent.com/renzodambrosi/Distilla/refs/heads/master/sites.json"
    }

    private val sp = context.getSharedPreferences("distilla", Context.MODE_PRIVATE)

    /**
     * Raw URL of sites.json on the Git server (GitHub, GitLab, Gitea...).
     * Used until the user changes it in Settings; if the user clears the field
     * it stays empty (online updates off), the default is not put back.
     */
    var remoteUrl: String
        get() = sp.getString("remote_url", DEFAULT_REMOTE_URL).orEmpty()
        set(v) = sp.edit { putString("remote_url", v.trim()) }

    /** Token facoltativo per repository privati (inviato come Authorization: Bearer). */
    var token: String
        get() = sp.getString("token", "").orEmpty()
        set(v) = sp.edit { putString("token", v.trim()) }

    var autoSync: Boolean
        get() = sp.getBoolean("auto_sync", true)
        set(v) = sp.edit { putBoolean("auto_sync", v) }

    var lastSync: Long
        get() = sp.getLong("last_sync", 0L)
        set(v) = sp.edit { putLong("last_sync", v) }

    var lastSyncMessage: String
        get() = sp.getString("last_sync_message", "").orEmpty()
        set(v) = sp.edit { putString("last_sync_message", v) }

    var speechRate: Float
        get() = sp.getFloat("speech_rate", 1.0f)
        set(v) = sp.edit { putFloat("speech_rate", v) }

    /** Reading history as JSON, see [io.github.renzodambrosi.distilla.core.History]. */
    var historyJson: String
        get() = sp.getString("history", "").orEmpty()
        set(v) = sp.edit { putString("history", v) }

    /** User-Agent sent when reading pages; a site rule can override it. Empty = default. */
    var userAgent: String
        get() = sp.getString("user_agent", null) ?: io.github.renzodambrosi.distilla.core.ArticleExtractor.USER_AGENT
        set(v) = sp.edit { putString("user_agent", v.trim()) }

    /** Retry with the browser engine when a site blocks the plain download (403/429/503). */
    var browserFallback: Boolean
        get() = sp.getBoolean("browser_fallback", true)
        set(v) = sp.edit { putBoolean("browser_fallback", v) }

    /** Audio channel for text-to-speech: false = media, true = navigation guidance. */
    var ttsGuidanceChannel: Boolean
        get() = sp.getBoolean("tts_guidance_channel", false)
        set(v) = sp.edit { putBoolean("tts_guidance_channel", v) }

    /** Listening queue as JSON, see [io.github.renzodambrosi.distilla.core.PlayQueue]. */
    var queueJson: String
        get() = sp.getString("queue", "").orEmpty()
        set(v) = sp.edit { putString("queue", v) }

    var fontSize: Int
        get() = sp.getInt("font_size", 18)
        set(v) = sp.edit { putInt("font_size", v) }
}
