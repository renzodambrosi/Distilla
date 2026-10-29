@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.renzodambrosi.distilla.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.renzodambrosi.distilla.R
import io.github.renzodambrosi.distilla.core.Article
import io.github.renzodambrosi.distilla.core.ArticleWarning
import io.github.renzodambrosi.distilla.core.ExtractionMethod

@Composable
fun ReaderScreen(vm: MainViewModel, onBack: () -> Unit) {
    val state by vm.reader.collectAsStateWithLifecycle()
    val fontSize by vm.fontSize.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    BackHandler(onBack = onBack)

    // Voice / queue notices (voice missing, story that couldn't be read...)
    PlaybackNoticeEffect(vm, snackbar)

    val article = (state as? ReaderState.Loaded)?.article
    val currentUrl = when (val s = state) {
        is ReaderState.Loaded -> s.article.url
        is ReaderState.Loading -> s.url
        is ReaderState.Failed -> s.url
        ReaderState.Idle -> null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    val smaller = stringResource(R.string.text_smaller)
                    val larger = stringResource(R.string.text_larger)
                    TextButton(
                        onClick = { vm.changeFontSize(-2) },
                        modifier = Modifier.semantics { contentDescription = smaller },
                    ) { Text("A−") }
                    TextButton(
                        onClick = { vm.changeFontSize(+2) },
                        modifier = Modifier.semantics { contentDescription = larger },
                    ) { Text("A+") }
                    IconButton(onClick = vm::reload) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.reload))
                    }
                    if (currentUrl != null) {
                        IconButton(onClick = { openInBrowser(context, currentUrl) }) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = stringResource(R.string.open_browser))
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (article == null) {
                MiniPlayer(vm, Modifier.navigationBarsPadding())
            } else {
                Column {
                    MiniPlayer(vm)
                    BottomAppBar {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            BarAction(Icons.Default.ContentCopy, stringResource(R.string.copy)) {
                                copyToClipboard(context, article.shareText)
                            }
                            BarAction(Icons.Default.Share, stringResource(R.string.share)) {
                                shareText(context, article.shareText, article.title)
                            }
                            BarAction(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.listen)) {
                                vm.playArticle(article)
                            }
                            BarAction(
                                Icons.Default.Edit,
                                stringResource(if (article.rule != null) R.string.rule else R.string.create_rule),
                            ) { vm.ruleFromArticle(article) }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                ReaderState.Idle -> Unit
                is ReaderState.Loading -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    if (s.resolving) {
                        Text(
                            stringResource(R.string.loading_resolving),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    if (s.withBrowser) {
                        Text(
                            stringResource(R.string.loading_browser),
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        s.url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                    )
                }
                is ReaderState.Failed -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.cannot_read), style = MaterialTheme.typography.titleMedium)
                    Text(s.message)
                    Text(s.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = vm::reload) { Text(stringResource(R.string.retry)) }
                        OutlinedButton(onClick = { openInBrowser(context, s.url) }) {
                            Text(stringResource(R.string.open_browser))
                        }
                    }
                }
                is ReaderState.Loaded -> key(s.article.url, s.article.method) { ArticleView(s.article, fontSize) }
            }
        }
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null)
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun methodLabel(a: Article): String = when (a.method) {
    ExtractionMethod.RULE -> stringResource(R.string.method_rule, a.rule?.displayName.orEmpty())
    ExtractionMethod.AUTO -> stringResource(R.string.method_auto)
    ExtractionMethod.JSON_LD -> stringResource(R.string.method_jsonld)
    ExtractionMethod.FULL_PAGE -> stringResource(R.string.method_full)
}

@Composable
private fun warningText(w: ArticleWarning): String = when (w) {
    is ArticleWarning.RuleNoMatch -> stringResource(R.string.warn_rule_no_match, w.ruleName)
    ArticleWarning.FullPage -> stringResource(R.string.warn_full_page)
}

@Composable
private fun ArticleView(a: Article, fontSize: Int) {
    SelectionContainer {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            if (a.title.isNotBlank()) {
                Text(
                    a.title,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontSize = (fontSize + 6).sp,
                        lineHeight = ((fontSize + 6) * 1.25).sp,
                    ),
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                "${a.host} · ${methodLabel(a)}" +
                    if (a.loadedWithBrowser) " · " + stringResource(R.string.via_browser) else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (a.warnings.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        a.warnings.forEach {
                            Text(
                                warningText(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                a.text,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.5).sp,
                ),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
