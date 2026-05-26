@file:OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.presentation.bookedit.delegates

import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.client.presentation.bookedit.EditableGenre
import com.calypsan.listenup.client.presentation.bookedit.EditableTag
import com.calypsan.listenup.client.presentation.bookedit.displayName
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import com.calypsan.listenup.core.Success

private val logger = KotlinLogging.logger {}

private const val SEARCH_DEBOUNCE_MS = 300L
private const val MIN_QUERY_LENGTH = 2
private const val SEARCH_LIMIT = 10

/**
 * Delegate handling genre and tag editing operations.
 *
 * Responsibilities:
 * - Genre filtering (local, from pre-loaded list)
 * - Tag search and selection (from pre-loaded list)
 * - Create new tags by entering raw text (normalized to slug)
 * - Add/remove genres and tags
 *
 * @property state Shared state flow owned by ViewModel
 * @property scope CoroutineScope for launching operations
 * @property onChangesMade Callback to notify ViewModel of changes
 */
class GenreTagEditDelegate(
    private val state: MutableStateFlow<BookEditUiState>,
    private val scope: CoroutineScope,
    private val onChangesMade: () -> Unit,
) {
    private val tagQueryFlow = MutableStateFlow("")

    init {
        setupTagSearch()
    }

    /**
     * Internal result type for the reactive tag search flow.
     */
    private sealed interface TagSearchFlowResult {
        data object Empty : TagSearchFlowResult

        data object Loading : TagSearchFlowResult

        /** Search succeeded; [results] is filtered to exclude tags already attached to the book. */
        data class Success(
            val results: List<EditableTag>,
        ) : TagSearchFlowResult
    }

    // ========== Genre Methods ==========

    /**
     * Update the genre search query and filter results locally.
     */
    fun updateGenreSearchQuery(query: String) {
        state.update { it.copy(genreSearchQuery = query) }
        filterGenres(query)
    }

    /**
     * Select a genre to add to the book.
     */
    fun selectGenre(genre: EditableGenre) {
        state.update { current ->
            // Check if already added
            if (current.genres.any { it.id == genre.id }) {
                return@update current.copy(
                    genreSearchQuery = "",
                    genreSearchResults = emptyList(),
                )
            }

            current.copy(
                genres = current.genres + genre,
                genreSearchQuery = "",
                genreSearchResults = emptyList(),
            )
        }
        onChangesMade()
    }

    /**
     * Remove a genre from the book.
     */
    fun removeGenre(genre: EditableGenre) {
        state.update { current ->
            current.copy(genres = current.genres.filter { it.id != genre.id })
        }
        onChangesMade()
    }

    // ========== Tag Methods ==========

    /**
     * Update the tag search query.
     */
    fun updateTagSearchQuery(query: String) {
        state.update { it.copy(tagSearchQuery = query) }
        tagQueryFlow.value = query
    }

    /**
     * Select a tag to add to the book.
     */
    fun selectTag(tag: EditableTag) {
        state.update { current ->
            // Check if already added
            if (current.tags.any { it.id == tag.id }) {
                return@update current.copy(
                    tagSearchQuery = "",
                    tagSearchResults = emptyList(),
                )
            }

            current.copy(
                tags = current.tags + tag,
                tagSearchQuery = "",
                tagSearchResults = emptyList(),
            )
        }
        tagQueryFlow.value = ""
        onChangesMade()
    }

    /**
     * Create a new tag inline and add it to the book.
     *
     * Tags are normalized to slugs (e.g., "Found Family" -> "found-family").
     * The actual tag creation happens on the server when the book is saved.
     */
    fun createAndAddTag(name: String) {
        if (name.isBlank()) return

        val slug = normalizeToSlug(name)
        if (slug.isEmpty()) return

        // Check if tag with same slug already exists in allTags
        val existingTag = state.value.allTags.find { it.slug == slug }

        if (existingTag != null) {
            // Use existing tag
            selectTag(existingTag)
            return
        }

        // Check if we already have this tag added
        if (state.value.tags.any { it.slug == slug }) {
            state.update {
                it.copy(
                    tagSearchQuery = "",
                    tagSearchResults = emptyList(),
                )
            }
            tagQueryFlow.value = ""
            return
        }

        // Create a new editable tag (no ID yet - will be assigned by server)
        val newTag = EditableTag(id = "", slug = slug)

        state.update { current ->
            current.copy(
                tags = current.tags + newTag,
                allTags = current.allTags + newTag, // Add to available tags
                tagSearchQuery = "",
                tagSearchResults = emptyList(),
            )
        }
        tagQueryFlow.value = ""
        onChangesMade()

        logger.info { "Added new tag: $slug" }
    }

    /**
     * Normalizes user input to a slug format.
     * "Found Family" -> "found-family"
     * "slow burn" -> "slow-burn"
     */
    private fun normalizeToSlug(input: String): String =
        input
            .trim()
            .lowercase()
            .replace(Regex("[\\s_/]+"), "-")
            .replace(Regex("[^a-z0-9-]"), "")
            .replace(Regex("-+"), "-")
            .trim('-')

    /**
     * Remove a tag from the book.
     */
    fun removeTag(tag: EditableTag) {
        state.update { current ->
            current.copy(tags = current.tags.filter { it.id != tag.id })
        }
        onChangesMade()
    }

    // ========== Private Methods ==========

    private fun filterGenres(query: String) {
        if (query.isBlank()) {
            state.update { it.copy(genreSearchResults = emptyList()) }
            return
        }

        val lowerQuery = query.lowercase()
        val currentGenreIds =
            state.value.genres
                .map { it.id }
                .toSet()

        val filtered =
            state.value.allGenres
                .filter { genre ->
                    genre.id !in currentGenreIds &&
                        (
                            genre.name.lowercase().contains(lowerQuery) ||
                                genre.path.lowercase().contains(lowerQuery)
                        )
                }.take(SEARCH_LIMIT)

        state.update { it.copy(genreSearchResults = filtered) }
    }

    private fun setupTagSearch() {
        tagQueryFlow
            .debounce(SEARCH_DEBOUNCE_MS)
            .distinctUntilChanged()
            .filter { it.length >= MIN_QUERY_LENGTH || it.isEmpty() }
            .flatMapLatest { query ->
                if (query.isBlank()) {
                    flowOf<TagSearchFlowResult>(TagSearchFlowResult.Empty)
                } else {
                    flow<TagSearchFlowResult> {
                        emit(TagSearchFlowResult.Loading)
                        emit(performTagSearch(query))
                    }
                }
            }.onEach { result ->
                when (result) {
                    is TagSearchFlowResult.Empty -> {
                        state.update {
                            it.copy(tagSearchResults = emptyList(), tagSearchLoading = false)
                        }
                    }

                    is TagSearchFlowResult.Loading -> {
                        state.update { it.copy(tagSearchLoading = true) }
                    }

                    is TagSearchFlowResult.Success -> {
                        state.update {
                            it.copy(tagSearchResults = result.results, tagSearchLoading = false)
                        }
                    }
                }
            }.launchIn(scope)
    }

    /**
     * Perform tag search and return the result.
     * Called from flatMapLatest flow - cancellation is handled automatically.
     */
    private fun performTagSearch(query: String): TagSearchFlowResult {
        // Filter from allTags (already loaded) by displayName
        val lowerQuery = query.lowercase()
        val currentSlugs =
            state.value.tags
                .map { it.slug }
                .toSet()

        val filtered =
            state.value.allTags
                .filter { tag ->
                    tag.slug !in currentSlugs &&
                        tag.displayName().lowercase().contains(lowerQuery)
                }.take(SEARCH_LIMIT)

        return TagSearchFlowResult.Success(filtered)
    }
}
