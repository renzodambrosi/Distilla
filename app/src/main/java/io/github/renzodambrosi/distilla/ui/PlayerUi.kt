package io.github.renzodambrosi.distilla.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.renzodambrosi.distilla.R
import io.github.renzodambrosi.distilla.playback.PlaybackBus
import java.text.NumberFormat

/** "1,5×" with the decimal separator of the user's language. */
fun speedLabel(speed: Float): String =
    NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }.format(speed) + "×"

/** Second line of the player: loading / paragraph X of N · language / finished. */
@Composable
fun playerStatus(now: NowPlaying, progress: PlaybackBus.Progress): String = when {
    now.ended -> stringResource(R.string.queue_ended)
    now.buffering || progress.loading -> stringResource(R.string.loading_story)
    progress.total > 0 -> {
        val p = stringResource(R.string.paragraph, progress.chunk + 1, progress.total)
        progress.language?.let { "$p · ${it.replaceFirstChar { c -> c.titlecase() }}" } ?: p
    }
    else -> now.currentItem?.host.orEmpty()
}

/**
 * Bar at the bottom of the screens while the queue has stories: title, paragraph,
 * play/pause, next, speed. A tap opens the queue screen.
 */
@Composable
fun MiniPlayer(vm: MainViewModel, modifier: Modifier = Modifier) {
    val now by vm.now.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val item = now.currentItem ?: return
    val openLabel = stringResource(R.string.queue_title)
    val speedDescription = stringResource(R.string.tts_speed)

    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier.fillMaxWidth()) {
        Column {
            if (progress.total > 0 && !now.ended) {
                LinearProgressIndicator(
                    progress = { (progress.chunk + 1f) / progress.total },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                Modifier
                    .clickable(onClickLabel = openLabel, onClick = vm::openQueue)
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title.ifBlank { item.host },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        playerStatus(now, progress) + " · " + (now.current + 1) + "/" + now.queue.size,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = vm::cycleSpeed,
                    modifier = Modifier.semantics { contentDescription = speedDescription },
                ) { Text(speedLabel(now.speed), style = MaterialTheme.typography.labelLarge) }
                PlayPauseButton(now, vm::togglePlay)
                IconButton(onClick = vm::next, enabled = now.current < now.queue.lastIndex) {
                    Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.tts_next))
                }
            }
        }
    }
}

@Composable
fun PlayPauseButton(now: NowPlaying, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        when {
            now.playing && now.buffering -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            now.playing -> Icon(Icons.Default.Pause, contentDescription = stringResource(R.string.tts_pause))
            else -> Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.tts_resume))
        }
    }
}

/**
 * Shows the player's notices (voice missing, story that couldn't be read...) as a
 * snackbar, with a shortcut to install voices or to the voice settings when useful.
 */
@Composable
fun PlaybackNoticeEffect(vm: MainViewModel, snackbar: SnackbarHostState) {
    val notice by vm.playbackNotice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val n = notice
    val text = n?.let {
        when (it.kind) {
            PlaybackBus.NoticeKind.TTS_UNAVAILABLE -> stringResource(R.string.tts_unavailable)
            PlaybackBus.NoticeKind.TTS_ERROR -> stringResource(R.string.tts_error)
            PlaybackBus.NoticeKind.LOAD_FAILED -> stringResource(R.string.load_failed, it.title.orEmpty())
            PlaybackBus.NoticeKind.VERIFY_NEEDED -> stringResource(R.string.notice_verify, it.title.orEmpty())
            PlaybackBus.NoticeKind.LANGUAGE_MISSING ->
                if (it.used != null) stringResource(R.string.tts_language_missing, it.wanted.orEmpty(), it.used)
                else stringResource(R.string.tts_language_none, it.wanted.orEmpty())
        }
    }
    val installLabel = stringResource(R.string.tts_install)
    val settingsLabel = stringResource(R.string.tts_settings)
    val verifyLabel = stringResource(R.string.verify_open)
    LaunchedEffect(n) {
        if (n == null) return@LaunchedEffect
        vm.consumePlaybackNotice()
        val action = when (n.kind) {
            PlaybackBus.NoticeKind.LANGUAGE_MISSING -> installLabel
            PlaybackBus.NoticeKind.TTS_UNAVAILABLE -> settingsLabel
            PlaybackBus.NoticeKind.VERIFY_NEEDED -> verifyLabel.takeIf { n.url != null }
            else -> null
        }
        val result = snackbar.showSnackbar(text.orEmpty(), actionLabel = action, duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) {
            when (n.kind) {
                PlaybackBus.NoticeKind.LANGUAGE_MISSING -> installTtsVoices(context)
                PlaybackBus.NoticeKind.VERIFY_NEEDED -> n.url?.let { vm.open(it) }
                else -> openTtsSettings(context)
            }
        }
    }
}
