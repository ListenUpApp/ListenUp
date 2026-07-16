package com.calypsan.listenup.client.features.storyworld

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.ListenUpTextField
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_rename
import listenup.composeapp.generated.resources.story_world_delete_entity
import listenup.composeapp.generated.resources.story_world_delete_entity_body
import listenup.composeapp.generated.resources.story_world_delete_entity_title
import listenup.composeapp.generated.resources.story_world_rename_entity_title

/**
 * Rename dialog reached from the entity detail hero's overflow menu — a name field prefilled
 * with [currentName]; confirm is disabled while the field is blank or unchanged.
 */
@Composable
fun RenameEntityDialog(
    currentName: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable(currentName) { mutableStateOf(currentName) }
    val trimmed = name.trim()

    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(stringResource(Res.string.story_world_rename_entity_title)) },
        text = {
            ListenUpTextField(
                value = name,
                onValueChange = { name = it },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(trimmed) },
                enabled = trimmed.isNotBlank() && trimmed != currentName,
            ) {
                Text(stringResource(Res.string.common_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
    )
}

/**
 * Delete-entity confirmation reached from the entity detail hero's overflow menu — the entity
 * page goes away, but per [Res.string.story_world_delete_entity_body] its log entries and every
 * mention of its name survive.
 */
@Composable
fun DeleteEntityDialog(
    entityName: String,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    ListenUpDestructiveDialog(
        onDismissRequest = onDismissRequest,
        title = stringResource(Res.string.story_world_delete_entity_title),
        text = stringResource(Res.string.story_world_delete_entity_body, entityName),
        confirmText = stringResource(Res.string.story_world_delete_entity),
        dismissText = stringResource(Res.string.common_cancel),
        onConfirm = onConfirm,
    )
}
