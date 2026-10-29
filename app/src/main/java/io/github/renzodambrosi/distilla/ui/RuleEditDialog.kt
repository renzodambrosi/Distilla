@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.renzodambrosi.distilla.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.renzodambrosi.distilla.R
import io.github.renzodambrosi.distilla.core.ArticleExtractor
import io.github.renzodambrosi.distilla.core.CssSelector
import io.github.renzodambrosi.distilla.core.FilterOrder
import io.github.renzodambrosi.distilla.core.RuleSource
import io.github.renzodambrosi.distilla.core.UrlMatcher

/**
 * Full-screen rule editor. "Save" sits in the top bar so it is always visible,
 * even on small screens or with the keyboard open; it is repeated at the end of the form.
 */
@Composable
fun RuleEditDialog(vm: MainViewModel, draft: RuleDraft) {
    var name by remember(draft) { mutableStateOf(draft.rule.name) }
    var pattern by remember(draft) { mutableStateOf(draft.rule.pattern) }
    var xpath by remember(draft) { mutableStateOf(draft.rule.xpath) }
    var include by remember(draft) { mutableStateOf(draft.rule.include) }
    var exclude by remember(draft) { mutableStateOf(draft.rule.exclude) }
    var order by remember(draft) { mutableStateOf(draft.rule.order) }
    var userAgent by remember(draft) { mutableStateOf(draft.rule.userAgent) }
    var useBrowser by remember(draft) { mutableStateOf(draft.rule.useBrowser) }
    var testUrl by remember(draft) { mutableStateOf(draft.testUrl) }
    val testing by vm.testing.collectAsStateWithLifecycle()
    val testResult by vm.testResult.collectAsStateWithLifecycle()

    val patternOk = pattern.isNotBlank()
    val xpathOk = remember(xpath) { xpath.isBlank() || ArticleExtractor.isValidXpath(xpath) }
    val includeOk = remember(include) { CssSelector.isValid(include) }
    val excludeOk = remember(exclude) { CssSelector.isValid(exclude) }
    val hasSelection = xpath.isNotBlank() || include.isNotBlank() || exclude.isNotBlank()
    // A rule with only a User-Agent is fine: the text is then found automatically
    val usable = hasSelection || userAgent.isNotBlank() || useBrowser
    val valid = patternOk && xpathOk && includeOk && excludeOk && usable
    val testMatches = remember(pattern, testUrl) {
        testUrl.isBlank() || pattern.isBlank() || UrlMatcher.matches(pattern, testUrl.trim())
    }
    val mono = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)

    fun current() = draft.rule.copy(
        name = name, pattern = pattern, xpath = xpath, include = include, exclude = exclude, order = order,
        userAgent = userAgent, useBrowser = useBrowser,
    )

    Dialog(
        onDismissRequest = vm::dismissDraft,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(if (draft.isNew) R.string.rule_new else R.string.rule_edit)) },
                    navigationIcon = {
                        IconButton(onClick = vm::dismissDraft) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel))
                        }
                    },
                    actions = {
                        TextButton(onClick = { vm.saveRule(current()) }, enabled = valid) {
                            Icon(Icons.Default.Check, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.save))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (draft.rule.source == RuleSource.REMOTE) {
                    Text(
                        stringResource(R.string.rule_remote_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.rule_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = { Text(stringResource(R.string.rule_pattern)) },
                    supportingText = { Text(stringResource(R.string.rule_pattern_hint)) },
                    isError = !patternOk,
                    singleLine = true,
                    textStyle = mono,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )

                // 1. XPath: the area of the page to consider (empty = whole page)
                OutlinedTextField(
                    value = xpath,
                    onValueChange = { xpath = it },
                    label = { Text(stringResource(R.string.rule_xpath)) },
                    supportingText = {
                        Text(stringResource(if (!xpathOk) R.string.rule_xpath_invalid else R.string.rule_xpath_hint))
                    },
                    isError = !xpathOk,
                    minLines = 2,
                    textStyle = mono,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 2. CSS filters inside that area
                OutlinedTextField(
                    value = include,
                    onValueChange = { include = it },
                    label = { Text(stringResource(R.string.rule_include)) },
                    supportingText = {
                        Text(stringResource(if (!includeOk) R.string.rule_css_invalid else R.string.rule_include_hint))
                    },
                    isError = !includeOk,
                    textStyle = mono,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = exclude,
                    onValueChange = { exclude = it },
                    label = { Text(stringResource(R.string.rule_exclude)) },
                    supportingText = {
                        Text(stringResource(if (!excludeOk) R.string.rule_css_invalid else R.string.rule_exclude_hint))
                    },
                    isError = !excludeOk,
                    textStyle = mono,
                    modifier = Modifier.fillMaxWidth(),
                )

                // 3. Priority between the two filters: only matters when both are set
                if (include.isNotBlank() && exclude.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.order_title))
                            Text(
                                stringResource(
                                    if (order == FilterOrder.EXCLUDE_FIRST) R.string.order_exclude_first_desc
                                    else R.string.order_include_first_desc
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(
                            checked = order == FilterOrder.EXCLUDE_FIRST,
                            onCheckedChange = { order = if (it) FilterOrder.EXCLUDE_FIRST else FilterOrder.INCLUDE_FIRST },
                        )
                    }
                }

                // 4. Optional User-Agent for this site only
                OutlinedTextField(
                    value = userAgent,
                    onValueChange = { userAgent = it },
                    label = { Text(stringResource(R.string.rule_user_agent)) },
                    supportingText = { Text(stringResource(R.string.rule_user_agent_hint)) },
                    minLines = 1,
                    maxLines = 4,
                    textStyle = mono,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { userAgent = ArticleExtractor.USER_AGENT }) { Text(stringResource(R.string.ua_preset_mobile)) }
                    TextButton(onClick = { userAgent = ArticleExtractor.USER_AGENT_DESKTOP }) { Text(stringResource(R.string.ua_preset_desktop)) }
                    if (userAgent.isNotBlank()) {
                        TextButton(onClick = { userAgent = "" }) { Text(stringResource(R.string.ua_clear)) }
                    }
                }

                // 5. Browser engine: for sites that block apps (403) or build the text with JavaScript
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.rule_browser))
                        Text(
                            stringResource(R.string.rule_browser_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = useBrowser, onCheckedChange = { useBrowser = it })
                }

                if (!usable) {
                    Text(
                        stringResource(R.string.rule_needs_selection),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                HorizontalDivider()

                OutlinedTextField(
                    value = testUrl,
                    onValueChange = { testUrl = it },
                    label = { Text(stringResource(R.string.rule_test_url)) },
                    singleLine = true,
                    isError = !testMatches,
                    supportingText = if (!testMatches) {
                        { Text(stringResource(R.string.rule_test_nomatch)) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { vm.testRule(testUrl, current()) },
                    enabled = testUrl.isNotBlank() && valid && !testing,
                ) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.rule_test))
                }
                testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

                HorizontalDivider()

                // Save again at the end of the form, next to Cancel
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { vm.saveRule(current()) }, enabled = valid, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.save))
                    }
                    OutlinedButton(onClick = vm::dismissDraft, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.cancel))
                    }
                }
                Spacer(Modifier.padding(bottom = 16.dp))
            }
        }
    }
}
