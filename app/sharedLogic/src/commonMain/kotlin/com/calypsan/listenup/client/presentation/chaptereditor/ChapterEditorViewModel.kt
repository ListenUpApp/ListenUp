package com.calypsan.listenup.client.presentation.chaptereditor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/**
 * The chapter editor for one book, shared by Android, iOS and web.
 *
 * State is a **projection**, not an accumulation: `observeChapters` is the source of truth and the
 * user's unsaved work is a [ChapterDraft] layered over it. That shape is what makes the awkward
 * case behave — a sync frame landing while someone is mid-edit updates the underlying set, the
 * draft stays exactly as it was, and `changedElsewhere` becomes true because the fork point no
 * longer matches. Nothing is silently overwritten, and nothing has to be latched and cleared by
 * hand from three different places.
 *
 * Scoped per-book: [bookId] arrives as a Koin parameter, because the editor is always entered for
 * one book and never switches.
 *
 * Every client drives it the same way — read [state], call the edit methods, collect [events] —
 * and each is responsible for calling [close] when the screen goes: Android through `onCleared`,
 * iOS from its wrapper's `isolated deinit`, web when its session closes. Without that the
 * `viewModelScope` outlives the screen and the save coroutine orphans.
 */
class ChapterEditorViewModel(
    private val bookId: String,
    bookRepository: BookRepository,
    private val bookEditRepository: BookEditRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    private var closed = false

    /** The user's unsaved work, or null while the editor is simply following the mirror. */
    private val draft = MutableStateFlow<ChapterDraft?>(null)
    private val selectedChapterId = MutableStateFlow<String?>(null)
    private val saving = MutableStateFlow(false)

    private val eventChannel = Channel<ChapterEditorEvent>(Channel.BUFFERED)

    /** Save outcomes, delivered once. Collect at the screen's entry point. */
    val events: Flow<ChapterEditorEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<ChapterEditorUiState> =
        combine(
            bookRepository.observeChapters(bookId),
            bookRepository.observeBookDetail(bookId),
            draft,
            selectedChapterId,
            saving,
        ) { mirrored, book, workingDraft, selected, isSaving ->
            if (book == null) {
                ChapterEditorUiState.Loading
            } else {
                ChapterEditorUiState.Editing(
                    bookTitle = book.title,
                    chapters = workingDraft?.chapters ?: mirrored,
                    bookDurationMs = book.duration,
                    selectedChapterId = selected,
                    isDirty = workingDraft?.isDirty == true,
                    canUndo = workingDraft?.canUndo == true,
                    isSaving = isSaving,
                    // Derived, not latched: true exactly while the draft's fork point disagrees
                    // with what the mirror now holds.
                    changedElsewhere = workingDraft != null && workingDraft.forkedFrom != mirrored,
                )
            }
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            ChapterEditorUiState.Loading,
        )

    /** Focuses one boundary; the list and the detail lane both follow it. */
    fun select(chapterId: String?) {
        selectedChapterId.value = chapterId
    }

    /** Moves [chapterId] to [newStartMs], clamped between its neighbours. */
    fun retime(
        chapterId: String,
        newStartMs: Long,
    ) = edit { chapters, duration -> chapters.retimed(chapterId, newStartMs, duration) }

    /** Nudges [chapterId] by [deltaMs] — the ± buttons and the arrow keys. */
    fun nudge(
        chapterId: String,
        deltaMs: Long,
    ) = edit { chapters, duration ->
        val current = chapters.firstOrNull { it.id == chapterId } ?: return@edit chapters
        chapters.retimed(chapterId, current.startTime + deltaMs, duration)
    }

    /** Takes the playhead's exact millisecond as [chapterId]'s start — snap-to-playhead. */
    fun snapToPlayhead(
        chapterId: String,
        playheadMs: Long,
    ) = edit { chapters, duration -> chapters.retimed(chapterId, playheadMs, duration) }

    /** Retitles [chapterId]. Blank titles are refused rather than stored. */
    fun retitle(
        chapterId: String,
        title: String,
    ) = edit { chapters, _ -> chapters.retitled(chapterId, title) }

    /**
     * Inserts a boundary at [atMs], splitting the chapter there.
     *
     * The id is minted here rather than server-side because the edit has to appear instantly and
     * survive going offline — the same reason every other edit in this app writes locally first.
     */
    fun addAt(
        atMs: Long,
        title: String,
    ) = edit { chapters, duration -> chapters.added(Uuid.random().toString(), title, atMs, duration) }

    /** Removes [chapterId], merging its span into the chapter before it. */
    fun remove(chapterId: String) = edit { chapters, duration -> chapters.removed(chapterId, duration) }

    /** Replaces the whole set — the commit step of drift correction. */
    fun replaceAll(chapters: List<Chapter>) = edit { _, duration -> chapters.withDerivedDurations(duration) }

    /** Steps back one edit. A no-op when there is nothing to undo. */
    fun undo() {
        draft.update { it?.undone() }
    }

    /** Throws every unsaved edit away and follows the mirror again. */
    fun resetToSource() {
        draft.value = null
    }

    /**
     * Saves the working set.
     *
     * On success the draft is dropped so the editor follows the mirror again — which is already
     * correct, because the repository writes the edit to Room before the round-trip. On failure the
     * draft is kept untouched: the user's work is the one thing a failed save must never cost them.
     */
    fun save() {
        val editing = state.value as? ChapterEditorUiState.Editing ?: return
        if (saving.value) return
        viewModelScope.launch {
            saving.value = true
            val result = bookEditRepository.setBookChapters(BookId(bookId), editing.chapters.map { it.toInput() })
            saving.value = false
            when (result) {
                is AppResult.Success -> {
                    draft.value = null
                    eventChannel.trySend(ChapterEditorEvent.Saved)
                }

                is AppResult.Failure -> {
                    errorBus.emit(result.error)
                    eventChannel.trySend(ChapterEditorEvent.SaveFailed(result.error))
                }
            }
        }
    }

    /**
     * Cancels this ViewModel's coroutines. Idempotent. Android reaches it via [onCleared]; iOS from
     * the screen wrapper's `isolated deinit`; web when the editor session closes.
     */
    fun close() {
        if (closed) return
        closed = true
        viewModelScope.cancel()
    }

    override fun onCleared() {
        close()
        super.onCleared()
    }

    /**
     * Applies [transform] to the working set, forking a draft from the mirror on the first edit.
     *
     * Reads the current chapters out of [state] rather than keeping a second copy, so there is
     * exactly one answer to "what is being edited". Edits only ever arrive from a screen that is
     * collecting [state], so it is `Editing` whenever this is reachable.
     */
    private fun edit(transform: (List<Chapter>, Long) -> List<Chapter>) {
        val editing = state.value as? ChapterEditorUiState.Editing ?: return
        val next = transform(editing.chapters, editing.bookDurationMs)
        draft.update { current ->
            (current ?: ChapterDraft(chapters = editing.chapters, forkedFrom = editing.chapters)).mutate(next)
        }
    }
}

/** The wire shape, carrying the grouping headers through untouched. */
private fun Chapter.toInput(): ChapterInput =
    ChapterInput(
        id = id,
        title = title,
        startTime = startTime,
        duration = duration,
        partTitle = partTitle,
        bookTitle = bookTitle,
    )
