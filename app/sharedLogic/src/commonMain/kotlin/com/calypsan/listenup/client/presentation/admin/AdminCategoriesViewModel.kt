package com.calypsan.listenup.client.presentation.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.GenreUpdate
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.repository.GenreRepository
import com.calypsan.listenup.client.core.error.ErrorMapper
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * ViewModel for the admin categories (genres) tree screen.
 *
 * Manages the hierarchical display of system genres with expand/collapse state.
 * Genres form a tree structure based on their materialized path (e.g., /fiction/fantasy).
 */
class AdminCategoriesViewModel(
    private val genreRepository: GenreRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<AdminCategoriesUiState>
        field = MutableStateFlow<AdminCategoriesUiState>(AdminCategoriesUiState.Loading)

    init {
        observeGenres()
    }

    /**
     * Observe genres from local database.
     * Live category updates return when this domain migrates to the renovated sync engine.
     */
    private fun observeGenres() {
        viewModelScope.launch {
            try {
                genreRepository.observeAll().collect { genres ->
                    logger.debug { "Genres updated: ${genres.size}" }
                    val tree = buildGenreTree(genres)
                    state.update { current ->
                        if (current is AdminCategoriesUiState.Ready) {
                            current.copy(
                                genres = genres,
                                tree = tree,
                                totalBookCount = genres.sumOf { it.bookCount },
                            )
                        } else {
                            // First emission (from Loading) or recovering from Error:
                            // transition to Ready with fresh data and default UI fields.
                            AdminCategoriesUiState.Ready(
                                genres = genres,
                                tree = tree,
                                totalBookCount = genres.sumOf { it.bookCount },
                            )
                        }
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Failed to observe genres" }
                state.value = AdminCategoriesUiState.Error(ErrorMapper.map(e))
            }
        }
    }

    /**
     * Toggle expand/collapse state for a genre node.
     */
    fun toggleExpanded(genreId: String) {
        updateReady { ready ->
            val currentExpanded = ready.expandedIds.toMutableSet()
            if (currentExpanded.contains(genreId)) {
                currentExpanded.remove(genreId)
            } else {
                currentExpanded.add(genreId)
            }
            ready.copy(expandedIds = currentExpanded)
        }
    }

    /**
     * Expand all nodes in the tree.
     */
    fun expandAll() {
        updateReady { ready ->
            val allIds = ready.genres.map { it.id }.toSet()
            ready.copy(expandedIds = allIds)
        }
    }

    /**
     * Collapse all nodes in the tree.
     */
    fun collapseAll() {
        updateReady { it.copy(expandedIds = emptySet()) }
    }

    /**
     * Create a new genre, optionally under a parent.
     */
    fun createGenre(
        name: String,
        parentId: String?,
    ) {
        viewModelScope.launch {
            updateReady { it.copy(isSaving = true, error = null) }
            when (val result = genreRepository.createGenre(name, parentId?.let(::GenreId))) {
                is AppResult.Success -> {
                    // Auto-expand parent so user sees the new child
                    if (parentId != null) {
                        updateReady { ready ->
                            val expanded = ready.expandedIds.toMutableSet()
                            expanded.add(parentId)
                            ready.copy(expandedIds = expanded)
                        }
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to create genre: ${result.error.message}" }
                    updateReady { it.copy(error = result.error) }
                }
            }
            updateReady { it.copy(isSaving = false) }
        }
    }

    /**
     * Rename an existing genre.
     */
    fun renameGenre(
        id: String,
        name: String,
    ) {
        viewModelScope.launch {
            updateReady { it.copy(isSaving = true, error = null) }
            when (val result = genreRepository.updateGenre(GenreId(id), GenreUpdate(name = name))) {
                is AppResult.Success -> { /* observed list will refresh via Flow */ }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to rename genre: ${result.error.message}" }
                    updateReady { it.copy(error = result.error) }
                }
            }
            updateReady { it.copy(isSaving = false) }
        }
    }

    /**
     * Delete a genre.
     */
    fun deleteGenre(id: String) {
        viewModelScope.launch {
            updateReady { it.copy(isSaving = true, error = null) }
            when (val result = genreRepository.deleteGenre(GenreId(id))) {
                is AppResult.Success -> { /* observed list will refresh via Flow */ }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to delete genre: ${result.error.message}" }
                    updateReady { it.copy(error = result.error) }
                }
            }
            updateReady { it.copy(isSaving = false) }
        }
    }

    /**
     * Move a genre to a new parent.
     */
    fun moveGenre(
        id: String,
        newParentId: String?,
    ) {
        viewModelScope.launch {
            updateReady { it.copy(isSaving = true, error = null) }
            when (val result = genreRepository.moveGenre(GenreId(id), newParentId?.let(::GenreId))) {
                is AppResult.Success -> {
                    // Auto-expand new parent so user sees the moved genre
                    if (newParentId != null) {
                        updateReady { ready ->
                            val expanded = ready.expandedIds.toMutableSet()
                            expanded.add(newParentId)
                            ready.copy(expandedIds = expanded)
                        }
                    }
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to move genre: ${result.error.message}" }
                    updateReady { it.copy(error = result.error) }
                }
            }
            updateReady { it.copy(isSaving = false) }
        }
    }

    /**
     * Merge [source] genre into [target]. Books linked to source move to target,
     * aliases re-point, source is tombstoned. Refuses when source has live
     * descendants — surfaces via [com.calypsan.listenup.api.error.GenreError.HasDescendants].
     */
    fun mergeGenres(
        source: String,
        target: String,
    ) {
        viewModelScope.launch {
            updateReady { it.copy(isSaving = true, error = null) }
            when (val result = genreRepository.mergeGenres(GenreId(source), GenreId(target))) {
                is AppResult.Success -> { /* observed list will refresh via Flow */ }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    logger.error { "Failed to merge genres: ${result.error.message}" }
                    updateReady { it.copy(error = result.error) }
                }
            }
            updateReady { it.copy(isSaving = false) }
        }
    }

    /**
     * Clear the error state.
     */
    fun clearError() {
        updateReady { it.copy(error = null) }
    }

    /**
     * Apply [transform] to state only if it is currently [AdminCategoriesUiState.Ready].
     * No-ops when state is [AdminCategoriesUiState.Loading] or [AdminCategoriesUiState.Error].
     */
    private fun updateReady(transform: (AdminCategoriesUiState.Ready) -> AdminCategoriesUiState.Ready) {
        state.update { current ->
            if (current is AdminCategoriesUiState.Ready) transform(current) else current
        }
    }

    /**
     * Build a tree structure from flat genre list.
     * Uses materialized path to determine hierarchy.
     */
    private fun buildGenreTree(genres: List<Genre>): List<GenreTreeNode> {
        // Group genres by parent path
        val childrenByParentPath = mutableMapOf<String, MutableList<Genre>>()

        genres.forEach { genre ->
            val parentPath = genre.path.substringBeforeLast('/', "")
            if (parentPath.isNotEmpty()) {
                childrenByParentPath.getOrPut(parentPath) { mutableListOf() }.add(genre)
            }
        }

        // Find root genres (those with single-segment paths like "/fiction")
        val roots =
            genres
                .filter { genre ->
                    val segments = genre.path.trim('/').split('/')
                    segments.size == 1
                }.sortedBy { it.name }

        // Build tree recursively
        fun buildNode(
            genre: Genre,
            depth: Int,
        ): GenreTreeNode {
            val children =
                childrenByParentPath[genre.path]
                    ?.sortedBy { it.name }
                    ?.map { buildNode(it, depth + 1) }
                    ?: emptyList()

            return GenreTreeNode(
                genre = genre,
                children = children,
                depth = depth,
            )
        }

        return roots.map { buildNode(it, 0) }
    }
}

/**
 * A node in the genre tree structure.
 */
data class GenreTreeNode(
    val genre: Genre,
    val children: List<GenreTreeNode>,
    val depth: Int,
)

/**
 * UI state for the admin categories screen.
 *
 * Sealed hierarchy:
 * - [Loading] before the first emission from `observeAll()`.
 * - [Ready] once genres have loaded; carries tree, expand/collapse state,
 *   a transient `error` for mutation failures surfaced in a snackbar, and
 *   an `isSaving` flag driving the top linear progress indicator.
 * - [Error] if the observe pipeline fails (terminal until the flow recovers).
 */
sealed interface AdminCategoriesUiState {
    data object Loading : AdminCategoriesUiState

    /** Genres have loaded; carries the tree, expand/collapse state, save overlay, and a transient `error`. */
    data class Ready(
        val isSaving: Boolean = false,
        val genres: List<Genre> = emptyList(),
        val tree: List<GenreTreeNode> = emptyList(),
        val expandedIds: Set<String> = emptySet(),
        val totalBookCount: Int = 0,
        val error: AppError? = null,
    ) : AdminCategoriesUiState

    /** Terminal state when the observe pipeline fails. */
    data class Error(
        val error: AppError,
    ) : AdminCategoriesUiState
}
