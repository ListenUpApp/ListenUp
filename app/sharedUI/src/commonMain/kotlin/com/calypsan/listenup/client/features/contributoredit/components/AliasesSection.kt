package com.calypsan.listenup.client.features.contributoredit.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMerge
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpButton
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.contributor_also_known_as
import listenup.composeapp.generated.resources.contributor_merge_button
import listenup.composeapp.generated.resources.contributor_no_aliases_hint
import listenup.composeapp.generated.resources.contributor_remove_aliasname
import listenup.composeapp.generated.resources.contributor_unmerge_aliasname
import listenup.composeapp.generated.resources.contributor_unmerge_body
import listenup.composeapp.generated.resources.contributor_unmerge_confirm
import org.jetbrains.compose.resources.stringResource

/**
 * Always-visible "Also Known As" section: a contributor's aliases (AKAs) with a
 * per-alias unmerge action and an inline merge button.
 *
 * Renders each alias as an [InputChip]. Tapping the trailing close icon opens a
 * confirmation dialog; confirming invokes [onUnmerge] with the alias name. The VM
 * dispatches the unmerge RPC; the sync event eventually removes the alias from this
 * list, which causes the parent screen to re-render.
 *
 * By design, aliases are merge/unmerge-only. There is no "add alias"
 * affordance — aliases appear only as a side effect of `mergeContributors`. The
 * [onMergeClick] button opens the merge flow; when [aliases] is empty, a hint
 * replaces the chip row so the section still reads as a field.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AliasesSection(
    aliases: List<String>,
    onUnmerge: (String) -> Unit,
    onMergeClick: () -> Unit,
) {
    ContributorStudioCard(title = stringResource(Res.string.contributor_also_known_as)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (aliases.isEmpty()) {
                Text(
                    text = stringResource(Res.string.contributor_no_aliases_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    aliases.forEach { alias ->
                        AliasChip(alias = alias, onUnmerge = { onUnmerge(alias) })
                    }
                }
            }
            ListenUpButton(
                text = stringResource(Res.string.contributor_merge_button),
                onClick = onMergeClick,
                filled = false,
                fillMaxWidth = false,
                leadingIcon = Icons.AutoMirrored.Filled.CallMerge,
            )
        }
    }
}

@Composable
private fun AliasChip(
    alias: String,
    onUnmerge: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }

    InputChip(
        selected = false,
        onClick = { },
        label = { Text(alias) },
        trailingIcon = {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(Res.string.contributor_remove_aliasname, alias),
                modifier =
                    Modifier
                        .size(InputChipDefaults.AvatarSize)
                        .clickable { showConfirm = true },
            )
        },
    )

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(Res.string.contributor_unmerge_aliasname, alias)) },
            text = { Text(stringResource(Res.string.contributor_unmerge_body, alias)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        onUnmerge()
                    },
                ) {
                    Text(stringResource(Res.string.contributor_unmerge_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text(stringResource(Res.string.common_cancel))
                }
            },
        )
    }
}
