package com.calypsan.listenup.client.presentation.admin.absimport

import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.data.remote.SearchApiContract
import com.calypsan.listenup.client.presentation.admin.ABSImportUiState
import com.calypsan.listenup.client.presentation.admin.BookMappingTab
import com.calypsan.listenup.client.presentation.admin.SelectedBookDisplay
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

private const val MIN_SEARCH_QUERY_LEN = 2
private const val SEARCH_LIMIT = 10

internal class BookMappingDelegate(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<ABSImportUiState>,
    private val errorBus: ErrorBus,
    private val searchApi: SearchApiContract,
) {
    fun setBookMapping(
        absItemId: String,
        listenupBookId: String?,
    ) {
        state.updateReady { current ->
            val newMappings = current.bookMappings.toMutableMap()
            if (listenupBookId != null) {
                newMappings[absItemId] = listenupBookId
            } else {
                newMappings.remove(absItemId)
            }
            current.copy(bookMappings = newMappings)
        }
    }

    /**
     * Set the active tab in the book mapping step.
     */
    fun setBookMappingTab(tab: BookMappingTab) {
        state.updateReady { it.copy(bookMappingTab = tab) }
    }

    /**
     * Called when a book search field gains focus.
     * Activates search for that specific book and clears previous search state.
     */
    fun activateBookSearch(absItemId: String) {
        state.updateReady {
            it.copy(
                activeSearchAbsItemId = absItemId,
                bookSearchQuery = "",
                bookSearchResults = emptyList(),
                isSearchingBooks = false,
            )
        }
    }

    /**
     * Called when a book search field loses focus.
     * Clears the active search state.
     */
    fun deactivateBookSearch() {
        state.updateReady {
            it.copy(
                activeSearchAbsItemId = null,
                bookSearchQuery = "",
                bookSearchResults = emptyList(),
                isSearchingBooks = false,
            )
        }
    }

    /**
     * Update search query for the active book search field.
     */
    fun updateBookSearchQuery(query: String) {
        state.updateReady { it.copy(bookSearchQuery = query) }

        if (query.length < MIN_SEARCH_QUERY_LEN) {
            state.updateReady { it.copy(bookSearchResults = emptyList(), isSearchingBooks = false) }
            return
        }

        scope.launch {
            state.updateReady { it.copy(isSearchingBooks = true) }
            try {
                val response =
                    searchApi.search(
                        query = query,
                        types = "book",
                        genres = null,
                        genrePath = null,
                        minDuration = null,
                        maxDuration = null,
                        limit = SEARCH_LIMIT,
                        offset = 0,
                    )
                state.updateReady {
                    it.copy(
                        bookSearchResults = response.hits,
                        isSearchingBooks = false,
                    )
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                errorBus.emit(ErrorMapper.map(e))
                logger.error(e) { "Book search failed: ${e.message}" }
                state.updateReady {
                    it.copy(
                        bookSearchResults = emptyList(),
                        isSearchingBooks = false,
                    )
                }
            }
        }
    }

    /**
     * Select a book from search results or suggestions and apply the mapping.
     */
    fun selectBook(
        absItemId: String,
        bookId: String,
        title: String,
        author: String?,
        durationMs: Long?,
    ) {
        scope.launch {
            // Show loading spinner on the tapped result while state propagates
            state.updateReady { it.copy(loadingBookItemId = bookId) }

            // Store display info for the selected book
            val displayInfo =
                SelectedBookDisplay(
                    bookId = bookId,
                    title = title,
                    author = author,
                    durationMs = durationMs,
                )

            state.updateReady { s ->
                val newDisplays = s.selectedBookDisplays.toMutableMap()
                newDisplays[absItemId] = displayInfo

                val newMappings = s.bookMappings.toMutableMap()
                newMappings[absItemId] = bookId

                s.copy(
                    selectedBookDisplays = newDisplays,
                    bookMappings = newMappings,
                    // Clear search state
                    activeSearchAbsItemId = null,
                    bookSearchQuery = "",
                    bookSearchResults = emptyList(),
                    loadingBookItemId = null,
                )
            }
        }
    }

    /**
     * Clear the book mapping for an ABS item (allows re-searching).
     */
    fun clearBookMapping(absItemId: String) {
        state.updateReady { s ->
            val newDisplays = s.selectedBookDisplays.toMutableMap()
            newDisplays.remove(absItemId)

            val newMappings = s.bookMappings.toMutableMap()
            newMappings.remove(absItemId)

            s.copy(
                selectedBookDisplays = newDisplays,
                bookMappings = newMappings,
            )
        }
    }
}
