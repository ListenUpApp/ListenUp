package com.calypsan.listenup.client.presentation.tagdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.model.BookListItem
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for the Tag Detail screen.
 *
 * Observes the tag and its book list reactively so the UI tracks SSE- and
 * sync-driven updates without re-loading. The screen supplies the tag id
 * via [loadTag]; the flow pipeline uses `flatMapLatest` to swap upstream
 * sources when the id changes, and `.stateIn(WhileSubscribed)` so the
 * pipeline is only hot while the screen is observing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TagDetailViewModel(
    private val tagRepository: TagRepository,
    private val bookRepository: BookRepository,
) : ViewModel() {
    private val tagIdFlow = MutableStateFlow<String?>(null)

    val state: StateFlow<TagDetailUiState> =
        tagIdFlow
            .flatMapLatest { tagId ->
                if (tagId == null) {
                    flowOf(TagDetailUiState.Idle)
                } else {
                    combine(
                        tagRepository.observeById(tagId),
                        tagRepository.observeBookIdsForTag(tagId),
                    ) { tag, bookIds ->
                        if (tag == null) {
                            TagDetailUiState.Error("Tag not found")
                        } else {
                            val books =
                                bookRepository
                                    .getBookListItems(bookIds)
                                    .sortedBy { it.title }
                            TagDetailUiState.Ready(
                                tagId = tagId,
                                tagName = tag.displayName(),
                                books = books,
                            )
                        }
                    }.onStart { emit(TagDetailUiState.Loading) }
                }
            }.distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = TagDetailUiState.Idle,
            )

    /** Set the tag to observe. Safe to call repeatedly with the same id. */
    fun loadTag(tagId: String) {
        tagIdFlow.value = tagId
    }
}

/**
 * UI state for the Tag Detail screen.
 */
sealed interface TagDetailUiState {
    /** No tag selected yet (pre-[TagDetailViewModel.loadTag]). */
    data object Idle : TagDetailUiState

    /** Upstream has not yet produced data for the selected tag. */
    data object Loading : TagDetailUiState

    /** Tag and books loaded. */
    data class Ready(
        val tagId: String,
        val tagName: String,
        val books: List<BookListItem>,
    ) : TagDetailUiState {
        val bookCount: Int get() = books.size
    }

    /** Tag not found or other load failure. */
    data class Error(
        val message: String,
    ) : TagDetailUiState
}
