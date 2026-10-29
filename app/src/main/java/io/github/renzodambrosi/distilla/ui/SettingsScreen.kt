@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.renzodambrosi.distilla.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.renzodambrosi.distilla.R
import io.github.renzodambrosi.distilla.core.ArticleExtractor
import io.github.renzodambrosi.distilla.core.RuleSource
import io.github.renzodambrosi.distilla.core.SiteRule

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val rules by vm.rules.collectAsStateWithLifecycle()
    val remoteUrl by vm.remoteUrl.collectAsStateWithLifecycle()
    val token by vm.token.collectAsStateWithLifecycle()
    val autoSync by vm.autoSync.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val syncMessage by vm.syncMessage.collectAsStateWithLifecycle()
    val conflicts by vm.conflicts.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<SiteRule?>(null) }
    val exportTitle = stringResource(R.string.export_sites)
    val importing by vm.importing.collectAsStateWithLifecycle()
    val userAgent by vm.userAgent.collectAsStateWithLifecycle()
    val browserFallback by vm.browserFallback.collectAsStateWithLifecycle()
    val ttsGuidance by vm.ttsGuidance.collectAsStateWithLifecycle()
    // System file picker. "*/*" because file managers often label .json files as text or binary.
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFromFile(uri)
    }
    // System "save as": the user picks folder and name (Download, Drive, SD card...)
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.exportToFile(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { shareText(context, vm.exportJson(), "sites.json") },
                        enabled = rules.isNotEmpty(),
                    ) { Icon(Icons.Default.Share, contentDescription = exportTitle) }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.newRule() },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.new_site)) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionTitle(stringResource(R.string.section_shared)) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.online_update))
                        Text(
                            stringResource(R.string.online_update_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = autoSync, onCheckedChange = vm::setAutoSync)
                }
            }
            item {
                OutlinedTextField(
                    value = remoteUrl,
                    onValueChange = vm::setRemoteUrl,
                    label = { Text(stringResource(R.string.remote_url_label)) },
                    placeholder = { Text("https://raw.githubusercontent.com/user/repo/main/sites.json") },
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = token,
                    onValueChange = vm::setToken,
                    label = { Text(stringResource(R.string.token_label)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Button(onClick = vm::syncNow, enabled = !syncing && remoteUrl.isNotBlank()) {
                    if (syncing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sync_now))
                }
            }
            if (syncMessage.isNotBlank()) {
                item { Text(syncMessage, style = MaterialTheme.typography.bodySmall) }
            }
            if (conflicts.isNotEmpty()) {
                item {
                    FilledTonalButton(
                        onClick = vm::showConflicts,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.resolve_conflicts, conflicts.size))
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.local_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { HorizontalDivider() }

            // Text to speech: audio channel (matters in the car / Android Auto)
            item { SectionTitle(stringResource(R.string.section_tts)) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.tts_guidance))
                        Text(
                            stringResource(R.string.tts_guidance_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = ttsGuidance, onCheckedChange = vm::setTtsGuidance)
                }
            }
            item { HorizontalDivider() }

            // Requests: User-Agent sent when reading pages (a site rule can set its own)
            item { SectionTitle(stringResource(R.string.section_requests)) }
            item {
                OutlinedTextField(
                    value = userAgent,
                    onValueChange = vm::setUserAgent,
                    label = { Text(stringResource(R.string.user_agent_label)) },
                    supportingText = { Text(stringResource(R.string.user_agent_hint)) },
                    maxLines = 4,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.setUserAgent(ArticleExtractor.USER_AGENT) }) {
                        Text(stringResource(R.string.ua_preset_mobile))
                    }
                    TextButton(onClick = { vm.setUserAgent(ArticleExtractor.USER_AGENT_DESKTOP) }) {
                        Text(stringResource(R.string.ua_preset_desktop))
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.browser_fallback))
                        Text(
                            stringResource(R.string.browser_fallback_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = browserFallback, onCheckedChange = vm::setBrowserFallback)
                }
            }
            item { HorizontalDivider() }
            item { SectionTitle(stringResource(R.string.section_sites, rules.size)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { pickFile.launch(arrayOf("*/*")) },
                        enabled = !importing,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (importing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.FileOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.import_file))
                    }
                    OutlinedButton(
                        onClick = { saveFile.launch(vm.exportFileName()) },
                        enabled = rules.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.SaveAlt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.export_file))
                    }
                }
                Text(
                    stringResource(R.string.import_file_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (rules.size > 5) {
                item {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        label = { Text(stringResource(R.string.search)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (rules.isEmpty()) {
                item { Text(stringResource(R.string.no_sites), style = MaterialTheme.typography.bodyMedium) }
            }
            val shown = rules.filter { r ->
                filter.isBlank() || listOf(r.name, r.pattern, r.xpath, r.include, r.exclude).any { it.contains(filter.trim(), ignoreCase = true) }
            }
            items(shown, key = { it.id }) { rule ->
                RuleCard(rule, onClick = { vm.editRule(rule) }, onDelete = { toDelete = rule })
            }
        }
    }

    toDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.delete_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r.displayName)
                    if (r.source == RuleSource.REMOTE) Text(stringResource(R.string.delete_remote_note))
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteRule(r); toDelete = null }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun RuleCard(rule: SiteRule, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        rule.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    SourceBadge(rule.source)
                }
                Spacer(Modifier.height(4.dp))
                Text(rule.pattern, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(
                    ruleConfigText(rule),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete))
            }
        }
    }
}

@Composable
private fun SourceBadge(source: RuleSource) {
    val user = source == RuleSource.USER
    Surface(
        shape = RoundedCornerShape(50),
        color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            stringResource(if (user) R.string.badge_local else R.string.badge_git),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
