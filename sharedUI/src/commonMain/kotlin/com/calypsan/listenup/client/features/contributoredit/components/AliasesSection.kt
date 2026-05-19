package com.calypsan.listenup.client.features.contributoredit.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.AutocompleteResultItem
import com.calypsan.listenup.client.design.components.ListenUpAutocompleteField
import com.calypsan.listenup.client.domain.model.ContributorSearchResult
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.contributor_no_pen_names_yet_add
import listenup.composeapp.generated.resources.contributor_remove_aliasname

/**
 * Aliases section with merge functionality.
 *
 * Add pen names to merge into this contributor.
 * E.g., Adding "Richard Bachman" to Stephen King will:
 * - Re-link all "Richard Bachman" books to Stephen King
 * - Delete the Richard Bachman contributor
 * - Store "Richard Bachman" as an alias name
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AliasesSection(
    aliases: List<String>,
    searchQuery: String,
    searchResults: List<ContributorSearchResult>,
    isSearching: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onAliasSelected: (ContributorSearchResult) -> Unit,
    onAliasEntered: (String) -> Unit,
    onRemoveAlias: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (aliases.isEmpty()) {
            Text(
                text = stringResource(Res.string.contributor_no_pen_names_yet_add),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                aliases.forEach { alias ->
                    AliasChip(
                        aliasName = alias,
                        onRemove = { onRemoveAlias(alias) },
                    )
                }
            }
        }

        // Search field for adding aliases
        ListenUpAutocompleteField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            results = searchResults,
            onResultSelected = { result -> onAliasSelected(result) },
            onSubmit = { query ->
                val topResult = searchResults.firstOrNull()
                if (topResult != null && topResult.name.equals(query, ignoreCase = true)) {
                    onAliasSelected(topResult)
                } else if (query.isNotBlank()) {
                    onAliasEntered(query)
                }
            },
            resultContent = { result ->
                AutocompleteResultItem(
                    name = result.name,
                    subtitle =
                        if (result.bookCount > 0) {
                            "${result.bookCount} ${if (result.bookCount == 1) "book" else "books"} will be merged"
                        } else {
                            null
                        },
                    onClick = { onAliasSelected(result) },
                )
            },
            placeholder = "Search contributor to merge, or type pen name...",
            isLoading = isSearching,
        )
    }
}

@Composable
private fun AliasChip(
    aliasName: String,
    onRemove: () -> Unit,
) {
    InputChip(
        selected = false,
        onClick = { },
        label = { Text(aliasName) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = null,
                modifier = Modifier.size(InputChipDefaults.AvatarSize),
            )
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(Res.string.contributor_remove_aliasname, aliasName),
                modifier =
                    Modifier
                        .size(InputChipDefaults.AvatarSize)
                        .clickable { onRemove() },
            )
        },
    )
}
