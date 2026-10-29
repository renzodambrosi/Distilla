@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.renzodambrosi.distilla.ui

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistAddCheck
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.renzodambrosi.distilla.R
import io.github.renzodambrosi.distilla.core.HistoryEntry
import io.github.renzodambrosi.distilla.core.UrlExtractor

@Composable
fun HomeScreen(vm: MainViewModel) {
    var url by rememberSaveable { mutableStateOf("") }
    val rules by vm.rules.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    PlaybackNoticeEffect(vm, snackbar)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { MiniPlayer(vm, Modifier.navigationBarsPadding()) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (now.queue.isNotEmpty()) {
                        IconButton(onClick = vm::openQueue) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = stringResource(R.string.queue_title))
                        }
                    }
                    IconButton(onClick = vm::openSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 24.dp),
        ) {
            item {
                Column(
                    Modifier.padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(R.string.home_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.home_hint), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text(stringResource(R.string.url_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (url.isNotBlank()) vm.open(url) }),
                        trailingIcon = {
                            IconButton(onClick = {
                                readClipboard(context)?.let { clip -> url = UrlExtractor.find(clip) ?: clip.trim() }
                            }) { Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.paste)) }
                        },
                    )
                    Button(onClick = { vm.open(url) }, enabled = url.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.read))
                    }
                    Text(
                        stringResource(R.string.sites_configured, rules.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (history.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(16.dp))
                    // Header: "delete all" first, as the leading action of the list,
                    // then the title and "listen to all" (reads them one after the other)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = stringResource(R.string.history_clear),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                        Text(
                            stringResource(R.string.history_title, history.size),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = vm::listenAll) {
                            Icon(Icons.AutoMirrored.Filled.PlaylistPlay, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.listen_all))
                        }
                    }
                    HorizontalDivider(Modifier.padding(end = 12.dp))
                }
                items(history, key = { it.url + it.time }) { entry ->
                    HistoryRow(
                        entry,
                        queued = now.contains(entry.url),
                        onOpen = { vm.open(entry.url) },
                        onEnqueue = { vm.enqueue(entry) },
                        onDelete = { vm.removeFromHistory(entry) },
                    )
                    HorizontalDivider(Modifier.padding(end = 12.dp))
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.history_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); confirmClear = false }) {
                    Text(stringResource(R.string.history_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun HistoryRow(
    entry: HistoryEntry,
    queued: Boolean,
    onOpen: () -> Unit,
    onEnqueue: () -> Unit,
    onDelete: () -> Unit,
) {
    // "5 minutes ago", "yesterday"... in the user's language
    val whenText = DateUtils.getRelativeTimeSpanString(
        entry.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.title.ifBlank { entry.url },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${entry.host} · $whenText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Add to the listening queue (a check mark when it is already queued)
        IconButton(onClick = onEnqueue, enabled = !queued) {
            Icon(
                if (queued) Icons.AutoMirrored.Filled.PlaylistAddCheck else Icons.AutoMirrored.Filled.PlaylistAdd,
                contentDescription = stringResource(if (queued) R.string.in_queue else R.string.enqueue),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.history_delete_item))
        }
    }
}
