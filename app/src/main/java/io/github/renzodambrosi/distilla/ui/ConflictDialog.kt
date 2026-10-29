package io.github.renzodambrosi.distilla.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.renzodambrosi.distilla.R
import io.github.renzodambrosi.distilla.core.ConflictOrigin
import io.github.renzodambrosi.distilla.core.FilterOrder
import io.github.renzodambrosi.distilla.core.SiteRule
import io.github.renzodambrosi.distilla.core.SyncConflict

/**
 * Asked for each Git rule whose URL already exists locally with a different configuration.
 * "for all" choices apply to every remaining conflict of this sync.
 */
@Composable
fun ConflictDialog(
    conflict: SyncConflict,
    position: Int,
    total: Int,
    onChoice: (ConflictChoice) -> Unit,
) {
    val remainingMany = total - position > 0
    val fromFile = conflict.origin == ConflictOrigin.FILE

    AlertDialog(
        onDismissRequest = { onChoice(ConflictChoice.LATER) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f),
        title = { Text(stringResource(if (fromFile) R.string.conflict_title_file else R.string.conflict_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (total > 1) {
                    Text(
                        stringResource(R.string.conflict_counter, position, total),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(stringResource(if (fromFile) R.string.conflict_body_file else R.string.conflict_body), style = MaterialTheme.typography.bodyMedium)
                Text(
                    conflict.local.displayName + "\n" + conflict.local.pattern,
                    style = MaterialTheme.typography.titleSmall,
                )
                RuleConfigBox(stringResource(R.string.conflict_local), conflict.local)
                RuleConfigBox(stringResource(if (fromFile) R.string.conflict_remote_file else R.string.conflict_remote), conflict.remote)

                HorizontalDivider(Modifier.padding(vertical = 4.dp))

                Button(onClick = { onChoice(ConflictChoice.KEEP_LOCAL) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.keep_local))
                }
                OutlinedButton(onClick = { onChoice(ConflictChoice.OVERWRITE) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.overwrite))
                }
                if (remainingMany) {
                    OutlinedButton(onClick = { onChoice(ConflictChoice.KEEP_LOCAL_ALL) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.keep_local_all))
                    }
                    OutlinedButton(onClick = { onChoice(ConflictChoice.OVERWRITE_ALL) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.overwrite_all))
                    }
                }
                // Online updates have nothing to do with a file the user imported
                if (!fromFile) {
                    TextButton(onClick = { onChoice(ConflictChoice.DISABLE_ONLINE) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.disable_online), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = { onChoice(ConflictChoice.LATER) }) { Text(stringResource(R.string.decide_later)) }
        },
    )
}

@Composable
private fun RuleConfigBox(label: String, rule: SiteRule) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                ruleConfigText(rule),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

/** XPath, include, exclude and priority of a rule, one per line; shared with the settings list. */
@Composable
fun ruleConfigText(rule: SiteRule): String = buildList {
    if (rule.hasSelection) {
        add(stringResource(R.string.cfg_xpath) + ": " + rule.xpath.ifBlank { stringResource(R.string.cfg_whole_page) })
    } else {
        add(stringResource(R.string.cfg_auto))
    }
    if (rule.include.isNotBlank()) add(stringResource(R.string.cfg_include) + ": " + rule.include)
    if (rule.exclude.isNotBlank()) add(stringResource(R.string.cfg_exclude) + ": " + rule.exclude)
    if (rule.include.isNotBlank() && rule.exclude.isNotBlank() && rule.order == FilterOrder.EXCLUDE_FIRST) {
        add(stringResource(R.string.order_title))
    }
    if (rule.userAgent.isNotBlank()) add(stringResource(R.string.cfg_user_agent) + ": " + rule.userAgent)
    if (rule.useBrowser) add(stringResource(R.string.rule_browser))
}.joinToString("\n")
