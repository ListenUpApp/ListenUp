package com.calypsan.listenup.client.features.bookedit.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.AutocompleteResultItem
import com.calypsan.listenup.client.design.components.ListenUpAutocompleteField
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.domain.model.ContributorSearchResult
import com.calypsan.listenup.client.presentation.bookedit.ContributorRole
import com.calypsan.listenup.client.presentation.bookedit.EditableContributor
import com.calypsan.listenup.client.presentation.bookedit.displayName
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_remove
import listenup.composeapp.generated.resources.book_edit_add_role
import listenup.composeapp.generated.resources.book_edit_add_trimmedquery
import listenup.composeapp.generated.resources.book_edit_from_this_book
import listenup.composeapp.generated.resources.book_edit_showing_offline_results
import listenup.composeapp.generated.resources.book_edit_this_will_remove_contributorstoremovecount

/**
 * Talent section for managing contributors by role.
 */
@Suppress("LongParameterList")
@Composable
fun TalentSection(
    visibleRoles: Set<ContributorRole>,
    availableRolesToAdd: List<ContributorRole>,
    contributorsForRole: (ContributorRole) -> List<EditableContributor>,
    roleSearchQueries: Map<ContributorRole, String>,
    roleSearchResults: Map<ContributorRole, List<ContributorSearchResult>>,
    roleSearchLoading: Map<ContributorRole, Boolean>,
    roleOfflineResults: Map<ContributorRole, Boolean>,
    onRoleSearchQueryChange: (ContributorRole, String) -> Unit,
    onContributorSelected: (ContributorRole, ContributorSearchResult) -> Unit,
    onContributorEntered: (ContributorRole, String) -> Unit,
    onRemoveContributor: (EditableContributor, ContributorRole) -> Unit,
    onAddRoleSection: (ContributorRole) -> Unit,
    onRemoveRoleSection: (ContributorRole) -> Unit,
) {
    var roleToRemove by remember { mutableStateOf<ContributorRole?>(null) }
    var contributorsToRemoveCount by remember { mutableStateOf(0) }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Show a section for each visible role
        visibleRoles.forEach { role ->
            val contributors = contributorsForRole(role)
            RoleContributorSection(
                role = role,
                contributors = contributors,
                searchQuery = roleSearchQueries[role] ?: "",
                searchResults = roleSearchResults[role] ?: emptyList(),
                isSearching = roleSearchLoading[role] ?: false,
                isOffline = roleOfflineResults[role] ?: false,
                onQueryChange = { query -> onRoleSearchQueryChange(role, query) },
                onResultSelected = { result -> onContributorSelected(role, result) },
                onNameEntered = { name -> onContributorEntered(role, name) },
                onRemoveContributor = { contributor -> onRemoveContributor(contributor, role) },
                onRemoveSection = {
                    if (contributors.size >= 2) {
                        roleToRemove = role
                        contributorsToRemoveCount = contributors.size
                    } else {
                        onRemoveRoleSection(role)
                    }
                },
            )
        }

        // Add Role button
        if (availableRolesToAdd.isNotEmpty()) {
            AddRoleButton(
                availableRoles = availableRolesToAdd,
                onRoleSelected = onAddRoleSection,
            )
        }
    }

    // Confirmation dialog
    roleToRemove?.let { role ->
        ListenUpDestructiveDialog(
            onDismissRequest = { roleToRemove = null },
            title = "Remove ${role.displayName}s?",
            text =
                stringResource(
                    Res.string.book_edit_this_will_remove_contributorstoremovecount,
                    contributorsToRemoveCount,
                ) +
                    "${role.displayName.lowercase()}${if (contributorsToRemoveCount > 1) "s" else ""} " +
                    stringResource(Res.string.book_edit_from_this_book),
            confirmText = stringResource(Res.string.common_remove),
            onConfirm = {
                onRemoveRoleSection(role)
                roleToRemove = null
            },
            onDismiss = { roleToRemove = null },
        )
    }
}

@Suppress("LongParameterList", "CognitiveComplexMethod")
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoleContributorSection(
    role: ContributorRole,
    contributors: List<EditableContributor>,
    searchQuery: String,
    searchResults: List<ContributorSearchResult>,
    isSearching: Boolean,
    isOffline: Boolean,
    onQueryChange: (String) -> Unit,
    onResultSelected: (ContributorSearchResult) -> Unit,
    onNameEntered: (String) -> Unit,
    onRemoveContributor: (EditableContributor) -> Unit,
    onRemoveSection: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Role header with remove button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${role.displayName}s",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
            )
            IconButton(
                onClick = onRemoveSection,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove ${role.displayName} section",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Contributor chips
        if (contributors.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                contributors.forEach { contributor ->
                    ContributorChip(
                        contributor = contributor,
                        onRemove = { onRemoveContributor(contributor) },
                    )
                }
            }
        }

        // Offline indicator
        if (isOffline && searchResults.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.book_edit_showing_offline_results),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Search field
        ListenUpAutocompleteField(
            value = searchQuery,
            onValueChange = onQueryChange,
            results = searchResults,
            onResultSelected = onResultSelected,
            onSubmit = { query ->
                val trimmed = query.trim()
                if (trimmed.isNotEmpty()) {
                    val topResult = searchResults.firstOrNull()
                    if (topResult != null) {
                        onResultSelected(topResult)
                    } else if (trimmed.length >= 2) {
                        onNameEntered(trimmed)
                    }
                }
            },
            resultContent = { result ->
                AutocompleteResultItem(
                    name = result.name,
                    subtitle =
                        if (result.bookCount > 0) {
                            "${result.bookCount} ${if (result.bookCount == 1) "book" else "books"}"
                        } else {
                            null
                        },
                    onClick = { onResultSelected(result) },
                )
            },
            placeholder = "Add ${role.displayName.lowercase()}...",
            isLoading = isSearching,
        )

        // Add new chip
        val trimmedQuery = searchQuery.trim()
        val hasExactMatch = searchResults.any { it.name.equals(trimmedQuery, ignoreCase = true) }
        if (trimmedQuery.length >= 2 && !isSearching && !hasExactMatch) {
            AssistChip(
                onClick = { onNameEntered(trimmedQuery) },
                label = { Text(stringResource(Res.string.book_edit_add_trimmedquery, trimmedQuery)) },
                leadingIcon = {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                },
            )
        }
    }
}

@Composable
private fun ContributorChip(
    contributor: EditableContributor,
    onRemove: () -> Unit,
) {
    InputChip(
        selected = false,
        onClick = { },
        label = { Text(contributor.name) },
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
                contentDescription = "Remove ${contributor.name}",
                modifier =
                    Modifier
                        .size(InputChipDefaults.AvatarSize)
                        .clickable { onRemove() },
            )
        },
    )
}

@Composable
private fun AddRoleButton(
    availableRoles: List<ContributorRole>,
    onRoleSelected: (ContributorRole) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(stringResource(Res.string.book_edit_add_role)) },
            leadingIcon = {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
            },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            availableRoles.forEach { role ->
                DropdownMenuItem(
                    text = { Text(role.displayName) },
                    onClick = {
                        expanded = false
                        onRoleSelected(role)
                    },
                )
            }
        }
    }
}
