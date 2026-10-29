package io.github.renzodambrosi.distilla.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import io.github.renzodambrosi.distilla.R

fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
    // From Android 13 the system already shows a confirmation
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}

fun readClipboard(context: Context): String? {
    val cm = context.getSystemService(ClipboardManager::class.java)
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

fun shareText(context: Context, text: String, subject: String? = null) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        if (subject != null) putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_chooser)))
}

fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_browser, Toast.LENGTH_SHORT).show()
    }
}

/** System text-to-speech settings (engine, voices, speed). */
fun openTtsSettings(context: Context) {
    val intents = listOf(
        Intent("com.android.settings.TTS_SETTINGS"),
        Intent(android.provider.Settings.ACTION_SETTINGS),
    )
    for (i in intents) {
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        }
    }
}

/** Opens the voice engine's screen to download voices for more languages. */
fun installTtsVoices(context: Context) {
    try {
        context.startActivity(
            Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: ActivityNotFoundException) {
        openTtsSettings(context)
    }
}
