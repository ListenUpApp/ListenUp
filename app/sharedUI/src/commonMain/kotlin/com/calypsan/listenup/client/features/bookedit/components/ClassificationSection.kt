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
import com.calypsan.listenup.client.presentation.bookedit.EditableCollection
import com.calypsan.listenup.client.presentation.bookedit.EditableGenre
import com.calypsan.listenup.client.presentation.bookedit.EditableMood
import com.calypsan.listenup.client.presentation.bookedit.EditableTag
import com.calypsan.listenup.client.presentation.bookedit.displayName
import com.calypsan.listenup.client.presentation.bookedit.parentPath
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_remove_name
import listenup.composeapp.generated.resources.book_detail_mood
import listenup.composeapp.generated.resources.book_detail_tags
import listenup.composeapp.generated.resources.book_edit_add_trimmedquery
import listenup.composeapp.generated.resources.common_collections
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
    moods: List<EditableMood>,
    moodSearchQuery: String,
    moodSearchResults: List<EditableMood>,
    isMoodSearching: Boolean,
    isMoodCreating: Boolean,
    isAdmin: Boolean,
    collections: List<EditableCollection>,
    collectionSearchQuery: String,
    collectionSearchResults: List<EditableCollection>,
    onGenreSearchQueryChange: (String) -> Unit,
    onGenreSelected: (EditableGenre) -> Unit,
    onRemoveGenre: (EditableGenre) -> Unit,
    onTagSearchQueryChange: (String) -> Unit,
    onTagSelected: (EditableTag) -> Unit,
    onTagEntered: (String) -> Unit,
    onRemoveTag: (EditableTag) -> Unit,
    onMoodSearchQueryChange: (String) -> Unit,
    onMoodSelected: (EditableMood) -> Unit,
    onMoodEntered: (String) -> Unit,
    onRemoveMood: (EditableMood) -> Unit,
    onCollectionSearchQueryChange: (String) -> Unit,
    onCollectionSelected: (EditableCollection) -> Unit,
    onRemoveCollection: (EditableCollection) -> Unit,
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

        // Moods subsection
        MoodsSubsection(
            moods = moods,
            searchQuery = moodSearchQuery,
            searchResults = moodSearchResults,
            isSearching = isMoodSearching,
            isCreating = isMoodCreating,
            onSearchQueryChange = onMoodSearchQueryChange,
            onMoodSelected = onMoodSelected,
            onMoodEntered = onMoodEntered,
            onRemoveMood = onRemoveMood,
        )

        // Collections subsection — admin-only (collection membership is an ACL operation).
        if (isAdmin) {
            CollectionsSubsection(
                collections = collections,
                searchQuery = collectionSearchQuery,
                searchResults = collectionSearchResults,
                onSearchQueryChange = onCollectionSearchQueryChange,
                onCollectionSelected = onCollectionSelected,
                onRemoveCollection = onRemoveCollection,
            )
        }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoodsSubsection(
    moods: List<EditableMood>,
    searchQuery: String,
    searchResults: List<EditableMood>,
    isSearching: Boolean,
    isCreating: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onMoodSelected: (EditableMood) -> Unit,
    onMoodEntered: (String) -> Unit,
    onRemoveMood: (EditableMood) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.book_detail_mood),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )

        if (moods.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                moods.forEach { mood ->
                    MoodChip(
                        mood = mood,
                        onRemove = { onRemoveMood(mood) },
                    )
                }
            }
        }

        ListenUpAutocompleteField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            results = searchResults,
            onResultSelected = { mood -> onMoodSelected(mood) },
            onSubmit = { query ->
                val trimmed = query.trim()
                if (trimmed.isNotEmpty()) {
                    val topResult = searchResults.firstOrNull()
                    if (topResult != null) {
                        onMoodSelected(topResult)
                    } else if (trimmed.length >= 2) {
                        onMoodEntered(trimmed)
                    }
                }
            },
            resultContent = { mood ->
                AutocompleteResultItem(
                    name = mood.displayName(),
                    subtitle = null,
                    onClick = { onMoodSelected(mood) },
                )
            },
            placeholder = "Add mood...",
            isLoading = isSearching || isCreating,
        )

        // Add new mood chip
        val trimmedQuery = searchQuery.trim()
        val hasMatch =
            searchResults.any {
                it.displayName().equals(trimmedQuery, ignoreCase = true)
            }
        val alreadyHasMood =
            moods.any {
                it.displayName().equals(trimmedQuery, ignoreCase = true)
            }
        @Suppress("ComplexCondition")
        if (trimmedQuery.length >= 2 && !isSearching && !isCreating && !hasMatch && !alreadyHasMood) {
            AssistChip(
                onClick = { onMoodEntered(trimmedQuery) },
                label = { Text(stringResource(Res.string.book_edit_add_trimmedquery, trimmedQuery)) },
                leadingIcon = {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CollectionsSubsection(
    collections: List<EditableCollection>,
    searchQuery: String,
    searchResults: List<EditableCollection>,
    onSearchQueryChange: (String) -> Unit,
    onCollectionSelected: (EditableCollection) -> Unit,
    onRemoveCollection: (EditableCollection) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(Res.string.common_collections),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
        )

        if (collections.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                collections.forEach { collection ->
                    CollectionChip(
                        collection = collection,
                        onRemove = { onRemoveCollection(collection) },
                    )
                }
            }
        }

        ListenUpAutocompleteField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            results = searchResults,
            onResultSelected = { collection -> onCollectionSelected(collection) },
            onSubmit = {
                val topResult = searchResults.firstOrNull()
                if (topResult != null) {
                    onCollectionSelected(topResult)
                }
            },
            resultContent = { collection ->
                AutocompleteResultItem(
                    name = collection.name,
                    subtitle = null,
                    onClick = { onCollectionSelected(collection) },
                )
            },
            placeholder = "Add collection...",
            isLoading = false,
        )
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
                contentDescription = stringResource(Res.string.common_remove_name, genre.name),
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
                contentDescription = stringResource(Res.string.common_remove_name, tag.displayName()),
                modifier =
                    Modifier
                        .size(InputChipDefaults.AvatarSize)
                        .clickable { onRemove() },
            )
        },
    )
}

@Composable
private fun MoodChip(
    mood: EditableMood,
    onRemove: () -> Unit,
) {
    InputChip(
        selected = false,
        onClick = { },
        label = { Text(mood.displayName()) },
        trailingIcon = {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(Res.string.common_remove_name, mood.displayName()),
                modifier =
                    Modifier
                        .size(InputChipDefaults.AvatarSize)
                        .clickable { onRemove() },
            )
        },
    )
}

@Composable
private fun CollectionChip(
    collection: EditableCollection,
    onRemove: () -> Unit,
) {
    InputChip(
        selected = false,
        onClick = { },
        label = { Text(collection.name) },
        trailingIcon = {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(Res.string.common_remove_name, collection.name),
                modifier =
                    Modifier
                        .size(InputChipDefaults.AvatarSize)
                        .clickable { onRemove() },
            )
        },
    )
}
