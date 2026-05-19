package com.calypsan.listenup.client.features.bookedit.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.AutocompleteResultItem
import com.calypsan.listenup.client.design.components.ListenUpAutocompleteField
import com.calypsan.listenup.client.presentation.bookedit.EditableGenre
import com.calypsan.listenup.client.presentation.bookedit.EditableTag
import com.calypsan.listenup.client.presentation.bookedit.displayName
import com.calypsan.listenup.client.presentation.bookedit.parentPath
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_tags
import listenup.composeapp.generated.resources.book_edit_add_trimmedquery
import listenup.composeapp.generated.resources.common_genres

/**
 * Classification section with genres and tags.
 */
@Suppress("LongParameterList")
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClassificationSection(
    genres: List<EditableGenre>,
    genreSearchQuery: String,
    genreSearchResults: List<EditableGenre>,
    tags: List<EditableTag>,
    tagSearchQuery: String,
    tagSearchResults: List<EditableTag>,
    isTagSearching: Boolean,
    isTagCreating: Boolean,
    onGenreSearchQueryChange: (String) -> Unit,
    onGenreSelected: (EditableGenre) -> Unit,
    onRemoveGenre: (EditableGenre) -> Unit,
    onTagSearchQueryChange: (String) -> Unit,
    onTagSelected: (EditableTag) -> Unit,
    onTagEntered: (String) -> Unit,
    onRemoveTag: (EditableTag) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // Genres subsection
        GenresSubsection(
            genres = genres,
            searchQuery = genreSearchQuery,
            searchResults = genreSearchResults,
            onSearchQueryChange = onGenreSearchQueryChange,
            onGenreSelected = onGenreSelected,
            onRemoveGenre = onRemoveGenre,
        )

        // Tags subsection
        TagsSubsection(
            tags = tags,
            searchQuery = tagSearchQuery,
            searchResults = tagSearchResults,
            isSearching = isTagSearching,
            isCreating = isTagCreating,
            onSearchQueryChange = onTagSearchQueryChange,
            onTagSelected = onTagSelected,
            onTagEntered = onTagEntered,
            onRemoveTag = onRemoveTag,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenresSubsection(
    genres: List<EditableGenre>,
    searchQuery: String,
    searchResults: List<EditableGenre>,
    onSearchQueryChange: (String) -> Unit,
    onGenreSelected: (EditableGenre) -> Unit,
    onRemoveGenre: (EditableGenre) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.common_genres),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )

        if (genres.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                genres.forEach { genre ->
                    GenreChip(
                        genre = genre,
                        onRemove = { onRemoveGenre(genre) },
                    )
                }
            }
        }

        ListenUpAutocompleteField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            results = searchResults,
            onResultSelected = { genre -> onGenreSelected(genre) },
            onSubmit = { query ->
                val topResult = searchResults.firstOrNull()
                if (topResult != null) {
                    onGenreSelected(topResult)
                }
            },
            resultContent = { genre ->
                AutocompleteResultItem(
                    name = genre.name,
                    subtitle = genre.parentPath,
                    onClick = { onGenreSelected(genre) },
                )
            },
            placeholder = "Add genre...",
            isLoading = false,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagsSubsection(
    tags: List<EditableTag>,
    searchQuery: String,
    searchResults: List<EditableTag>,
    isSearching: Boolean,
    isCreating: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onTagSelected: (EditableTag) -> Unit,
    onTagEntered: (String) -> Unit,
    onRemoveTag: (EditableTag) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.book_detail_tags),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )

        if (tags.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tags.forEach { tag ->
                    TagChip(
                        tag = tag,
                        onRemove = { onRemoveTag(tag) },
                    )
                }
            }
        }

        ListenUpAutocompleteField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            results = searchResults,
            onResultSelected = { tag -> onTagSelected(tag) },
            onSubmit = { query ->
                val trimmed = query.trim()
                if (trimmed.isNotEmpty()) {
                    val topResult = searchResults.firstOrNull()
                    if (topResult != null) {
                        onTagSelected(topResult)
                    } else if (trimmed.length >= 2) {
                        onTagEntered(trimmed)
                    }
                }
            },
            resultContent = { tag ->
                AutocompleteResultItem(
                    name = tag.displayName(),
                    subtitle = null,
                    onClick = { onTagSelected(tag) },
                )
            },
            placeholder = "Add tag...",
            isLoading = isSearching || isCreating,
        )

        // Add new tag chip
        val trimmedQuery = searchQuery.trim()
        val hasMatch =
            searchResults.any {
                it.displayName().equals(trimmedQuery, ignoreCase = true)
            }
        val alreadyHasTag =
            tags.any {
                it.displayName().equals(trimmedQuery, ignoreCase = true)
            }
        @Suppress("ComplexCondition")
        if (trimmedQuery.length >= 2 && !isSearching && !isCreating && !hasMatch && !alreadyHasTag) {
            AssistChip(
                onClick = { onTagEntered(trimmedQuery) },
                label = { Text(stringResource(Res.string.book_edit_add_trimmedquery, trimmedQuery)) },
                leadingIcon = {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                },
            )
        }
    }
}

@Composable
private fun GenreChip(
    genre: EditableGenre,
    onRemove: () -> Unit,
) {
    InputChip(
        selected = false,
        onClick = { },
        label = { Text(genre.name) },
        trailingIcon = {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove ${genre.name}",
                modifier =
                    Modifier
                        .size(InputChipDefaults.AvatarSize)
                        .clickable { onRemove() },
            )
        },
    )
}

@Composable
private fun TagChip(
    tag: EditableTag,
    onRemove: () -> Unit,
) {
    InputChip(
        selected = false,
        onClick = { },
        label = { Text(tag.displayName()) },
        trailingIcon = {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove ${tag.displayName()}",
                modifier =
                    Modifier
                        .size(InputChipDefaults.AvatarSize)
                        .clickable { onRemove() },
            )
        },
    )
}
