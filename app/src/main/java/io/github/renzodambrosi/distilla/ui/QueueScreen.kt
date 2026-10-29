@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.renzodambrosi.distilla.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.renzodambrosi.distilla.R
import io.github.renzodambrosi.distilla.core.QueueItem

/** The listening queue: what is being read, what comes next; reorder, remove, jump. */
@Composable
fun QueueScreen(vm: MainViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val now by vm.now.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmClear by remember { mutableStateOf(false) }
    PlaybackNoticeEffect(vm, snackbar)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.queue_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (now.queue.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = stringResource(R.string.queue_clear),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
        bottomBar = { if (now.queue.isNotEmpty()) Controls(vm, now, progress) },
    ) { padding ->
        if (now.queue.isEmpty()) {
            Text(
                stringResource(R.string.queue_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(padding).padding(24.dp),
            )
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(now.queue, key = { _, item -> item.url }) { index, item ->
                QueueRow(
                    item = item,
                    index = index,
                    isCurrent = index == now.current,
                    isLast = index == now.queue.lastIndex,
                    onPlay = { vm.playQueueItem(index) },
                    onUp = { vm.moveInQueue(index, index - 1) },
                    onDown = { vm.moveInQueue(index, index + 1) },
                    onRemove = { vm.removeFromQueue(index) },
                )
                HorizontalDivider()
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.queue_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { vm.clearQueue(); confirmClear = false }) { Text(stringResource(R.string.queue_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun QueueRow(
    item: QueueItem,
    index: Int,
    isCurrent: Boolean,
    isLast: Boolean,
    onPlay: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val highlight = if (isCurrent) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Row(
        Modifier
            .fillMaxWidth()
            .background(highlight)
            .clickable(onClick = onPlay)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isCurrent) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = stringResource(R.string.now_reading))
        } else {
            Text("${index + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title.ifBlank { item.url },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(item.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onUp, enabled = index > 0) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.move_up))
        }
        IconButton(onClick = onDown, enabled = !isLast) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.move_down))
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.remove_from_queue))
        }
    }
}

/** Large controls at the bottom of the queue screen. */
@Composable
private fun Controls(vm: MainViewModel, now: NowPlaying, progress: io.github.renzodambrosi.distilla.playback.PlaybackBus.Progress) {
    val item = now.currentItem ?: return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                item.title.ifBlank { item.host },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(playerStatus(now, progress), style = MaterialTheme.typography.bodySmall)
            if (progress.total > 0 && !now.ended) {
                LinearProgressIndicator(
                    progress = { (progress.chunk + 1f) / progress.total },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = vm::cycleSpeed) { Text(speedLabel(now.speed), style = MaterialTheme.typography.titleMedium) }
                IconButton(onClick = vm::previous) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = stringResource(R.string.tts_previous), modifier = Modifier.size(32.dp))
                }
                PlayPauseButton(now, vm::togglePlay, Modifier.size(56.dp))
                IconButton(onClick = vm::next, enabled = now.current < now.queue.lastIndex) {
                    Icon(Icons.Default.SkipNext, contentDescription = stringResource(R.string.tts_next), modifier = Modifier.size(32.dp))
                }
                // Keeps the row symmetric with the speed button
                Spacer(Modifier.width(48.dp))
            }
        }
    }
}
