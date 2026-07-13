package com.calypsan.listenup.client.presentation.chaptereditor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.ChapterInput
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.chapter.ChapterAnchor
import com.calypsan.listenup.client.domain.chapter.DriftResult
import com.calypsan.listenup.client.domain.chapter.correctDrift
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.domain.MAX_TIER_LABEL
import com.calypsan.listenup.domain.TierLabels
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.uuid.Uuid

/** Minimum gap, in milliseconds, [ChapterEditorViewModel.add] requires from any existing chapter boundary. */
private const val MIN_CHAPTER_DURATION_MS = 50L * 1000

/**
 * ViewModel for the Chapter Editor screen — owns the in-memory draft chapter set for one book,
 * its undo history, dirty tracking, and the save round-trip to the server.
 *
 * Scoped per-book (constructed with [bookId] as a Koin `params.get()`, mirroring
 * `BookReadersViewModel`) rather than book-switching like `BookDetailViewModel` — the screen this
 * backs is always entered for a single book.
 *
 * [bookRepository]'s `observeChapters`/`observeBookTierLabels` flows are the source of truth for
 * the server-confirmed chapter set; [state] layers a local draft on top. Edits never write
 * through to Room — they accumulate in the draft until [save] round-trips them via
 * [bookEditRepository], with the authoritative state arriving back through the SSE sync engine
 * (which re-seeds the draft, since a successful [save] leaves the state not dirty).
 */
class ChapterEditorViewModel(
    private val bookId: String,
    private val bookRepository: BookRepository,
    private val bookEditRepository: BookEditRepository,
    private val errorBus: ErrorBus,
) : ViewModel() {
    val state: StateFlow<ChapterEditorUiState>
        field = MutableStateFlow<ChapterEditorUiState>(ChapterEditorUiState.Loading)

    private val _events = Channel<ChapterEditorEvent>(Channel.BUFFERED)

    /** One-shot save-outcome events — collect once at the screen entry point. */
    val events: Flow<ChapterEditorEvent> = _events.receiveAsFlow()

    /** LIFO history of pre-mutation snapshots; every non-drift edit pushes one frame before mutating. */
    private var undoStack: MutableList<UndoFrame> = mutableListOf()

    /** The last-observed, non-dirty source from [bookRepository] — the target of [resetToSource]. */
    private var sourceSnapshot: UndoFrame? = null

    /**
     * The book's total duration in milliseconds, fixed at the most recent seed (the last
     * chapter's derived end). The upper contiguity bound for [retime]/[add]/[remove].
     */
    private var bookDurationMs: Long = 0L

    init {
        viewModelScope.launch {
            combine(
                bookRepository.observeChapters(bookId),
                bookRepository.observeBookTierLabels(bookId),
            ) { chapters, tierLabels -> chapters to tierLabels }
                .collect { (chapters, tierLabels) ->
                    when (val current = state.value) {
                        is ChapterEditorUiState.Loading -> {
                            seedFrom(chapters, tierLabels)
                        }

                        is ChapterEditorUiState.Editing -> {
                            if (current.isDirty) {
                                // Never-stranded: an incoming sync frame must never silently
                                // discard in-progress user work — just flag it as stale.
                                state.value = current.copy(changedElsewhere = true)
                            } else {
                                seedFrom(chapters, tierLabels)
                            }
                        }

                        is ChapterEditorUiState.Error -> {
                            Unit
                        }
                    }
                }
        }
    }

    /** Reseeds the draft/tier labels/undo history from a fresh, non-dirty [bookRepository] emission. */
    private fun seedFrom(
        chapters: List<Chapter>,
        tierLabels: TierLabels,
    ) {
        bookDurationMs = chapters.maxOfOrNull { it.startTime + it.duration } ?: 0L
        undoStack = mutableListOf()
        sourceSnapshot = UndoFrame(chapters, tierLabels)
        state.value =
            ChapterEditorUiState.Editing(
                draft = chapters,
                tierLabels = tierLabels,
            )
    }

    // ---- Editing operations --------------------------------------------------------------

    /**
     * Moves [chapterId]'s start time to [newStartMs], clamped to the open interval between its
     * neighbors, then re-derives every chapter's duration from contiguity.
     */
    fun retime(
        chapterId: String,
        newStartMs: Long,
    ) = mutateDraft { draft ->
        val idx = draft.indexOfFirst { it.id == chapterId }
        if (idx == -1) return@mutateDraft null
        val lowerBound = (if (idx == 0) 0L else draft[idx - 1].startTime) + 1
        val upperBoundRaw = if (idx == draft.lastIndex) bookDurationMs else draft[idx + 1].startTime
        val upperBound = (upperBoundRaw - 1).coerceAtLeast(lowerBound)
        val clamped = newStartMs.coerceIn(lowerBound, upperBound)
        val retimed = draft.toMutableList()
        retimed[idx] = retimed[idx].copy(startTime = clamped)
        deriveContiguousDurations(retimed)
    }

    /** Renames [chapterId]. Silently rejected (no-op) on a blank title or one over [ChapterInput.MAX_TITLE]. */
    fun rename(
        chapterId: String,
        title: String,
    ) {
        if (title.isBlank() || title.length > ChapterInput.MAX_TITLE) return
        mutateDraft { draft ->
            val idx = draft.indexOfFirst { it.id == chapterId }
            if (idx == -1) return@mutateDraft null
            val renamed = draft.toMutableList()
            renamed[idx] = renamed[idx].copy(title = title)
            renamed
        }
    }

    /**
     * Inserts a new chapter at [atMs] with a placeholder title, then re-sorts and re-derives
     * durations. No-op if [atMs] lands within [MIN_CHAPTER_DURATION_MS] of an existing boundary.
     */
    fun add(atMs: Long) =
        mutateDraft { draft ->
            val clampedAt = atMs.coerceIn(0L, bookDurationMs)
            val tooClose = draft.any { abs(it.startTime - clampedAt) < MIN_CHAPTER_DURATION_MS }
            if (tooClose) return@mutateDraft null
            // TODO(Task 5): route through the string catalog as `chapter_editor_new_chapter_title`.
            val newChapter =
                Chapter(
                    id = Uuid.random().toString(),
                    title = "New Chapter",
                    duration = 0L,
                    startTime = clampedAt,
                )
            deriveContiguousDurations((draft + newChapter).sortedBy { it.startTime })
        }

    /**
     * Deletes [chapterId]; its span merges into the preceding chapter (or, when removing the
     * first chapter, the new first chapter absorbs the gap down to `0`). No-op on the last
     * remaining chapter.
     */
    fun remove(chapterId: String) =
        mutateDraft { draft ->
            if (draft.size <= 1) return@mutateDraft null
            val idx = draft.indexOfFirst { it.id == chapterId }
            if (idx == -1) return@mutateDraft null
            val remaining = draft.filterIndexed { i, _ -> i != idx }.toMutableList()
            if (idx == 0 && remaining.isNotEmpty()) {
                remaining[0] = remaining[0].copy(startTime = 0L)
            }
            deriveContiguousDurations(remaining)
        }

    /** Renames the book's [tier] vocabulary. Blank clears to `null`; over [MAX_TIER_LABEL] is rejected. */
    fun setTierLabel(
        tier: TierKind,
        label: String?,
    ) {
        val normalized = label?.takeUnless { it.isBlank() }
        if (normalized != null && normalized.length > MAX_TIER_LABEL) return
        mutateTierLabels { current ->
            when (tier) {
                TierKind.BOOK -> current.copy(bookTierLabel = normalized)
                TierKind.PART -> current.copy(partTierLabel = normalized)
            }
        }
    }

    /**
     * Sets [chapterId]'s free-form per-chapter section headers. Never invents a value — `null`
     * clears a header, and a blank string is normalized to `null`. Callers must never pass a
     * computed or prefixed string.
     */
    fun setSectionLabel(
        chapterId: String,
        partTitle: String?,
        bookTitle: String?,
    ) {
        val normalizedPart = partTitle?.takeUnless { it.isBlank() }
        val normalizedBook = bookTitle?.takeUnless { it.isBlank() }
        mutateDraft { draft ->
            val idx = draft.indexOfFirst { it.id == chapterId }
            if (idx == -1) return@mutateDraft null
            val relabelled = draft.toMutableList()
            relabelled[idx] = relabelled[idx].copy(partTitle = normalizedPart, bookTitle = normalizedBook)
            relabelled
        }
    }

    /**
     * Minimal drag-reorder primitive for the Task-4 adapter: repositions [movedId] to sit at
     * [newIndex] within the draft list, and adopts [newParentId]'s own section labels (or clears
     * them when [newParentId] is `null`/unknown) — a thin, single-undo-frame combination of a
     * positional move and a label copy from an *existing* chapter, never a synthesized string.
     * The real grouping semantics (what counts as a valid "parent") are the Task-4 adapter's
     * concern; this VM only supplies the mechanical parts.
     */
    fun reparent(
        movedId: String,
        newParentId: String?,
        newIndex: Int,
    ) = mutateDraft { draft ->
        val idx = draft.indexOfFirst { it.id == movedId }
        if (idx == -1) return@mutateDraft null
        val parent = newParentId?.let { pid -> draft.firstOrNull { it.id == pid } }
        val without = draft.toMutableList()
        val moved =
            without.removeAt(idx).copy(
                partTitle = parent?.partTitle,
                bookTitle = parent?.bookTitle,
            )
        without.add(newIndex.coerceIn(0, without.size), moved)
        without
    }

    /**
     * Dry-run [correctDrift] against the current draft — does not mutate [state] or push undo.
     * The caller renders the ghost preview from the result and, if accepted, calls [commitDrift].
     */
    fun applyDrift(
        anchors: List<ChapterAnchor>,
        lockedIds: Set<String>,
    ): DriftPreview {
        val draft =
            (state.value as? ChapterEditorUiState.Editing)?.draft
                ?: return DriftPreview.Rejected(DriftResult.Rejected.BadAnchors)
        return when (val result = correctDrift(draft, anchors, bookDurationMs, lockedIds)) {
            is DriftResult.Corrected -> DriftPreview.Ghosts(result.chapters)
            is DriftResult.Rejected -> DriftPreview.Rejected(result)
        }
    }

    /** Re-runs [correctDrift] and, on [DriftResult.Corrected], replaces the draft as exactly one undo frame. */
    fun commitDrift(
        anchors: List<ChapterAnchor>,
        lockedIds: Set<String>,
    ) = mutateDraft { draft ->
        when (val result = correctDrift(draft, anchors, bookDurationMs, lockedIds)) {
            is DriftResult.Corrected -> result.chapters
            is DriftResult.Rejected -> null
        }
    }

    /** Pops the most recent undo frame; no-op (not an error) when the stack is empty. */
    fun undo() {
        viewModelScope.launch {
            val current = state.value as? ChapterEditorUiState.Editing ?: return@launch
            val frame = undoStack.removeLastOrNull() ?: return@launch
            state.value =
                current.copy(
                    draft = frame.draft,
                    tierLabels = frame.tierLabels,
                    canUndo = undoStack.isNotEmpty(),
                    isDirty = undoStack.isNotEmpty(),
                )
        }
    }

    /** Discards every local edit, restoring [sourceSnapshot] and clearing the undo stack. */
    fun resetToSource() {
        viewModelScope.launch {
            val current = state.value as? ChapterEditorUiState.Editing ?: return@launch
            val snapshot = sourceSnapshot ?: return@launch
            undoStack = mutableListOf()
            state.value =
                current.copy(
                    draft = snapshot.draft,
                    tierLabels = snapshot.tierLabels,
                    canUndo = false,
                    isDirty = false,
                    changedElsewhere = false,
                )
        }
    }

    /**
     * Saves the draft: [BookEditRepository.setBookChapters] and [BookEditRepository.setBookTierLabels]
     * run concurrently. On both succeeding, clears the dirty flag and the undo stack and emits
     * [ChapterEditorEvent.SavedSuccessfully]. On either failing, the draft is retained untouched,
     * and the typed error is emitted to [errorBus] and as [ChapterEditorEvent.SaveFailed].
     */
    fun save() {
        viewModelScope.launch {
            val current = state.value as? ChapterEditorUiState.Editing ?: return@launch
            state.value = current.copy(isSaving = true)
            val id = BookId(bookId)
            val inputs = current.draft.map { it.toChapterInput() }

            val (chaptersResult, tierLabelsResult) =
                coroutineScope {
                    val chaptersDeferred = async { bookEditRepository.setBookChapters(id, inputs) }
                    val tierLabelsDeferred =
                        async {
                            bookEditRepository.setBookTierLabels(
                                id,
                                current.tierLabels.bookTierLabel,
                                current.tierLabels.partTierLabel,
                            )
                        }
                    chaptersDeferred.await() to tierLabelsDeferred.await()
                }

            // Re-read state: a books-domain update may have landed as `changedElsewhere` while
            // the save was in flight; preserve whatever else changed, only touch save-owned fields.
            val afterSaving = state.value as? ChapterEditorUiState.Editing ?: current
            if (chaptersResult is AppResult.Success && tierLabelsResult is AppResult.Success) {
                undoStack = mutableListOf()
                state.value = afterSaving.copy(isSaving = false, isDirty = false, canUndo = false)
                _events.trySend(ChapterEditorEvent.SavedSuccessfully)
            } else {
                val error =
                    (chaptersResult as? AppResult.Failure)?.error
                        ?: (tierLabelsResult as? AppResult.Failure)?.error
                state.value = afterSaving.copy(isSaving = false)
                if (error != null) {
                    errorBus.emit(error)
                    _events.trySend(ChapterEditorEvent.SaveFailed(error))
                }
            }
        }
    }

    // ---- Internals --------------------------------------------------------------------------

    /** Applies [transform] to the current draft; pushes one undo frame and marks dirty when it returns non-null. */
    private fun mutateDraft(transform: (List<Chapter>) -> List<Chapter>?) {
        viewModelScope.launch {
            val current = state.value as? ChapterEditorUiState.Editing ?: return@launch
            val newDraft = transform(current.draft) ?: return@launch
            undoStack.add(UndoFrame(current.draft, current.tierLabels))
            state.value = current.copy(draft = newDraft, isDirty = true, canUndo = true)
        }
    }

    /** Applies [transform] to the current tier labels; pushes one undo frame and marks dirty when non-null. */
    private fun mutateTierLabels(transform: (TierLabels) -> TierLabels?) {
        viewModelScope.launch {
            val current = state.value as? ChapterEditorUiState.Editing ?: return@launch
            val newLabels = transform(current.tierLabels) ?: return@launch
            undoStack.add(UndoFrame(current.draft, current.tierLabels))
            state.value = current.copy(tierLabels = newLabels, isDirty = true, canUndo = true)
        }
    }

    /** Recomputes every chapter's duration from contiguity: own end = next chapter's start, last end = [bookDurationMs]. */
    private fun deriveContiguousDurations(chapters: List<Chapter>): List<Chapter> =
        chapters.mapIndexed { i, chapter ->
            val end = if (i == chapters.lastIndex) bookDurationMs else chapters[i + 1].startTime
            chapter.copy(duration = (end - chapter.startTime).coerceAtLeast(0L))
        }

    private fun Chapter.toChapterInput(): ChapterInput =
        ChapterInput(
            id = id,
            title = title,
            startTime = startTime,
            duration = duration,
            partTitle = partTitle,
            bookTitle = bookTitle,
        )
}

/** A pre-mutation snapshot pushed onto the undo stack by every non-drift edit. */
private data class UndoFrame(
    val draft: List<Chapter>,
    val tierLabels: TierLabels,
)

/** Which of a book's two chapter-grouping tiers [ChapterEditorViewModel.setTierLabel] renames. */
enum class TierKind {
    /** The outer tier — [TierLabels.bookTierLabel]. */
    BOOK,

    /** The inner tier — [TierLabels.partTierLabel]. */
    PART,
}

/** UI state for the Chapter Editor screen. */
sealed interface ChapterEditorUiState {
    /** Pre-load placeholder before the first [BookRepository.observeChapters] emission. */
    data object Loading : ChapterEditorUiState

    /** The book's chapters are loaded and editable. */
    data class Editing(
        val draft: List<Chapter>,
        val tierLabels: TierLabels,
        val canUndo: Boolean = false,
        val isDirty: Boolean = false,
        val isSaving: Boolean = false,
        val changedElsewhere: Boolean = false,
    ) : ChapterEditorUiState

    /** Load failure. */
    data class Error(
        val message: String,
    ) : ChapterEditorUiState
}

/** One-shot save-outcome events emitted by [ChapterEditorViewModel]. */
sealed interface ChapterEditorEvent {
    /** Both `setBookChapters` and `setBookTierLabels` succeeded. */
    data object SavedSuccessfully : ChapterEditorEvent

    /**
     * Reserved for a future cheap offline-detection fast path. Not currently emitted — this VM
     * lets the RPC attempt run and fail through [SaveFailed] instead, which already guarantees
     * the draft survives (see [ChapterEditorViewModel.save] KDoc).
     */
    data object OfflineBlocked : ChapterEditorEvent

    /** `setBookChapters` or `setBookTierLabels` failed. */
    data class SaveFailed(
        val error: AppError,
    ) : ChapterEditorEvent
}

/** Outcome of [ChapterEditorViewModel.applyDrift]'s dry-run. */
sealed interface DriftPreview {
    /** The corrected, not-yet-committed chapter set to render as a preview overlay. */
    data class Ghosts(
        val chapters: List<Chapter>,
    ) : DriftPreview

    /** The anchors could not be applied; carries the typed reason for the UI to explain. */
    data class Rejected(
        val reason: DriftResult.Rejected,
    ) : DriftPreview
}
