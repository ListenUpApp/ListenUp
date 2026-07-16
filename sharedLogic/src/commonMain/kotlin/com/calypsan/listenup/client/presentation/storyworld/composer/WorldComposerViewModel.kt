package com.calypsan.listenup.client.presentation.storyworld.composer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.dto.world.WorldEventUpsert
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.map
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.Entity
import com.calypsan.listenup.client.domain.model.NewWorldEvent
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.EntityEditRepository
import com.calypsan.listenup.client.domain.repository.SeriesRepository
import com.calypsan.listenup.client.domain.repository.WorldEventEditRepository
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.presentation.storyworld.AnchorLabel
import com.calypsan.listenup.client.presentation.storyworld.AnchorLabeler
import com.calypsan.listenup.client.presentation.storyworld.EntityCard
import com.calypsan.listenup.client.presentation.storyworld.WorldRef
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Where a Story World log entry becomes visible while listening — the anchor the composer sheet
 * lets the author pick, and the shape [WorldComposerViewModel.save] translates into a
 * `(bookId, positionMs)` pair.
 *
 * Public: the composer sheet's UI consumes this shape directly to render/drive the anchor picker.
 */
sealed interface AnchorSelection {
    /** Anchored to the book currently playing, at its live playhead position. */
    data class Playhead(
        val bookId: String,
        val positionMs: Long,
    ) : AnchorSelection

    /** Anchored to the very start of [bookId] (position 0). */
    data class BeginningOfBook(
        val bookId: String,
    ) : AnchorSelection

    /** Anchored to the end of the chapter containing [positionMs] in [bookId]. */
    data class EndOfCurrentChapter(
        val bookId: String,
        val positionMs: Long,
    ) : AnchorSelection

    /** No book anchor at all — always visible regardless of listening progress. */
    data object AlwaysVisible : AnchorSelection

    /** Anchored to an author-chosen position in [bookId] (e.g. picked from a chapter/time list). */
    data class Custom(
        val bookId: String,
        val positionMs: Long,
    ) : AnchorSelection
}

/**
 * One of this world's books, exposed for the composer's anchor picker (see
 * [ComposerUiState.worldBooks]). The UI maps this to
 * [com.calypsan.listenup.client.features.storyworld.AnchorBook] — that type lives in `sharedUI`,
 * so this ViewModel can't reference it directly.
 *
 * @property sequenceLabel The series-sequence number label (e.g. "1"), or null for a
 *   standalone-book world.
 */
data class ComposerWorldBook(
    val id: String,
    val title: String,
    val sequenceLabel: String?,
    val durationMs: Long,
)

/**
 * A live-parsed or stored typed assertion the composer chip renders, resolved to display names.
 *
 * @property subjectName [com.calypsan.listenup.client.domain.model.WorldEvent.subjectEntityId]'s
 *   live (or id-fallback) display name.
 * @property objectName [com.calypsan.listenup.client.domain.model.WorldEvent.objectEntityId]'s
 *   live (or id-fallback) display name, or null when the assertion carries no object.
 */
data class AssertionUi(
    val type: WorldEventType,
    val subjectName: String,
    val objectName: String?,
)

/**
 * UI state for the Story World composer sheet — one screen, one shape (unlike the per-screen
 * sealed hierarchies elsewhere in the app): the composer is sheet-scoped editor state, not a
 * loading/ready/error progression.
 *
 * @property displayText The text field's content, mentions collapsed to their display name.
 * @property cursor The text field's caret position, in [displayText] coordinates.
 * @property mentionSpans Display-text ranges the UI paints as mention chips.
 * @property suggestions Entity-mention suggestions for an open `@`/`[` trigger; empty otherwise.
 * @property verbSuggestions Verb-phrase suggestions for an open `*` trigger; empty otherwise.
 * @property showQuickCreate Whether to offer "create a new entity named …" for the open mention trigger.
 * @property quickCreateQuery The in-progress mention query to prefill the quick-create affordance with.
 * @property assertion The detected (and undismissed) typed assertion, or null when none applies.
 * @property anchor The currently selected visibility anchor.
 * @property anchorSummary A human-facing description of [anchor]'s position in its book.
 * @property canSave Whether [WorldComposerViewModel.save] would produce a non-trivial event.
 * @property isEditMode Whether the sheet is editing an existing event rather than creating one.
 * @property worldBooks This world's books in reading order, for the anchor picker's
 *   "beginning of the book" list and the position scrubber's book switcher.
 * @property worldBookChapters [worldBooks]' chapters, keyed by book id — lets the position
 *   scrubber sheet switch books locally without a VM round trip per drag.
 * @property playheadSnapshot The live "where you are now" anchor, or null when the listener
 *   isn't currently playing a book in this world. Independent of [anchor] — offered by the
 *   anchor picker even after the author has picked something else.
 * @property playheadLabel [playheadSnapshot]'s resolved label, or null alongside it.
 * @property endOfChapterOption The "end of the current chapter" anchor derived from
 *   [playheadSnapshot], or null when unavailable (nothing playing, or the position falls
 *   outside every known chapter).
 */
data class ComposerUiState(
    val displayText: String,
    val cursor: Int,
    val mentionSpans: List<IntRange>,
    val suggestions: List<EntityCard>,
    val verbSuggestions: List<String>,
    val showQuickCreate: Boolean,
    val quickCreateQuery: String,
    val assertion: AssertionUi?,
    val anchor: AnchorSelection,
    val anchorSummary: AnchorLabel,
    val canSave: Boolean,
    val isEditMode: Boolean,
    val worldBooks: List<ComposerWorldBook>,
    val worldBookChapters: Map<String, List<Chapter>>,
    val playheadSnapshot: AnchorSelection.Playhead?,
    val playheadLabel: AnchorLabel?,
    val endOfChapterOption: AnchorSelection.EndOfCurrentChapter?,
) {
    companion object {
        /** The sheet's state before [WorldComposerViewModel.start] has produced anything. */
        fun empty(): ComposerUiState =
            ComposerUiState(
                displayText = "",
                cursor = 0,
                mentionSpans = emptyList(),
                suggestions = emptyList(),
                verbSuggestions = emptyList(),
                showQuickCreate = false,
                quickCreateQuery = "",
                assertion = null,
                anchor = AnchorSelection.AlwaysVisible,
                anchorSummary = AnchorLabel.AlwaysVisible,
                canSave = false,
                isEditMode = false,
                worldBooks = emptyList(),
                worldBookChapters = emptyMap(),
                playheadSnapshot = null,
                playheadLabel = null,
                endOfChapterOption = null,
            )
    }
}

/** One-shot events [WorldComposerViewModel] emits for the hosting UI to react to. */
sealed interface WorldComposerEvent {
    /** The note saved successfully — the UI should close the composer sheet. */
    data object Saved : WorldComposerEvent
}

/** Static, ordered verb-phrase vocabulary offered for an open `*` trigger. */
private val VERB_PHRASES =
    listOf("enters", "moves to", "arrives at", "travels to", "departs", "leaves")

/**
 * The composer's own mutable editor state — held directly (not repository-derived) since the
 * document a user is actively typing is local state, not a projection of Room. Everything
 * repository-derived (world entities, anchor book labels, anchor chapters) is combined in
 * reactively alongside this via [WorldComposerViewModel.state].
 */
private data class EditorState(
    val document: ComposerDocument,
    val anchor: AnchorSelection,
    /** The event's stored typed columns at load time, in edit mode only — see class KDoc rule. */
    val storedAssertion: Assertion?,
    /** True until the first document edit; while true, the chip renders [storedAssertion] verbatim. */
    val usingStoredAssertion: Boolean,
    val dismissed: Boolean,
    /** The assertion value that was dismissed, so a later document change can tell if it changed. */
    val lastDismissedAssertion: Assertion?,
    val isEditMode: Boolean,
    val editEventId: String?,
    val saving: Boolean,
) {
    companion object {
        fun initial(): EditorState =
            EditorState(
                document = ComposerDocument.empty(),
                anchor = AnchorSelection.AlwaysVisible,
                storedAssertion = null,
                usingStoredAssertion = false,
                dismissed = false,
                lastDismissedAssertion = null,
                isEditMode = false,
                editEventId = null,
                saving = false,
            )
    }
}

/**
 * ViewModel for the Story World composer sheet: the free-text note editor that detects `@`/`[`
 * entity mentions and `*` typed-verb assertions inline, and records or edits a
 * [com.calypsan.listenup.client.domain.model.WorldEvent] on save.
 *
 * The document itself ([ComposerDocument]) is local editor state, mutated imperatively by the
 * `accept*`/`on*` intent functions below — it is not a reactive projection of any repository.
 * Everything that IS repository-derived (the world's entities for mention suggestions, the
 * anchor's book label, the anchor's chapters) is combined in reactively via [state]'s
 * `combine(...).stateIn(...)`, per the rubric's `stateIn(WhileSubscribed)` rule.
 *
 * The assertion chip has one hard rule (spec ["Story World feature"]): in edit mode, the chip
 * renders the event's STORED typed columns verbatim until the user edits the text — it never
 * re-derives from a fresh parse of already-stored prose. The instant the user edits, that
 * guarantee is replaced by the normal live-parse behavior every create-mode note already has.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorldComposerViewModel(
    private val entityEditRepository: EntityEditRepository,
    private val worldEventEditRepository: WorldEventEditRepository,
    private val seriesRepository: SeriesRepository,
    private val bookRepository: BookRepository,
    private val playbackManager: PlaybackManager,
    private val errorBus: ErrorBus,
) : ViewModel() {
    private val worldFlow = MutableStateFlow<WorldRef?>(null)
    private val editorFlow = MutableStateFlow(EditorState.initial())

    private val savedEvents = Channel<WorldComposerEvent>(Channel.BUFFERED)

    /** One-shot events for the hosting UI — see [WorldComposerEvent]. */
    val events: Flow<WorldComposerEvent> = savedEvents.receiveAsFlow()

    private val worldEntitiesFlow: Flow<List<Entity>> =
        worldFlow.flatMapLatest { world ->
            when {
                world == null -> flowOf(emptyList())
                world.seriesId != null -> entityEditRepository.observeEntitiesForSeries(world.seriesId)
                else -> entityEditRepository.observeEntitiesForBook(world.bookId!!)
            }
        }

    private val worldBooksFlow: Flow<List<ComposerWorldBook>> =
        worldFlow.flatMapLatest { world -> world?.let(::observeWorldBooks) ?: flowOf(emptyList()) }

    private val worldBookLabelsFlow: Flow<Map<String, String>> =
        worldBooksFlow.map { books -> books.associate { it.id to (it.sequenceLabel ?: it.title) } }

    private val anchorChaptersFlow: Flow<List<Chapter>> =
        editorFlow
            .map { it.anchor.toAnchorPair().first }
            .distinctUntilChanged()
            .flatMapLatest { bookId -> bookId?.let(bookRepository::observeChapters) ?: flowOf(emptyList()) }

    /**
     * All of this world's books' chapters, keyed by book id — lets the position scrubber sheet
     * switch books locally (see [ComposerUiState.worldBookChapters]) without a VM round trip.
     */
    private val worldBookChaptersFlow: Flow<Map<String, List<Chapter>>> =
        worldBooksFlow.flatMapLatest { books ->
            if (books.isEmpty()) {
                flowOf(emptyMap())
            } else {
                combine(
                    books.map { book ->
                        bookRepository.observeChapters(book.id).map { chapters -> book.id to chapters }
                    },
                ) { pairs -> pairs.toMap() }
            }
        }

    /** The live playhead anchor, offered by the picker independent of the author's chosen [AnchorSelection]. */
    private val playheadSnapshotFlow: Flow<AnchorSelection.Playhead?> =
        combine(
            worldBooksFlow,
            playbackManager.currentBookId,
            playbackManager.currentPositionMs,
        ) { books, bookId, positionMs ->
            if (bookId != null && books.any { it.id == bookId.value }) {
                AnchorSelection.Playhead(bookId.value, positionMs)
            } else {
                null
            }
        }.distinctUntilChanged()

    private val playheadChaptersFlow: Flow<List<Chapter>> =
        playheadSnapshotFlow
            .map { it?.bookId }
            .distinctUntilChanged()
            .flatMapLatest { bookId -> bookId?.let(bookRepository::observeChapters) ?: flowOf(emptyList()) }

    private val playheadLabelFlow: Flow<AnchorLabel?> =
        combine(playheadSnapshotFlow, worldBookLabelsFlow, playheadChaptersFlow) { playhead, bookLabels, chapters ->
            playhead?.let { AnchorLabeler.label(bookLabels[it.bookId], chapters, it.positionMs) }
        }

    /** "End of the current chapter" derived from the live playhead — null when unavailable. */
    private val endOfChapterOptionFlow: Flow<AnchorSelection.EndOfCurrentChapter?> =
        combine(playheadSnapshotFlow, playheadChaptersFlow) { playhead, chapters ->
            if (playhead == null) {
                null
            } else {
                chapters
                    .lastOrNull { it.startTime <= playhead.positionMs }
                    ?.takeIf { playhead.positionMs < it.startTime + it.duration }
                    ?.let { chapter ->
                        AnchorSelection.EndOfCurrentChapter(
                            playhead.bookId,
                            chapter.startTime + chapter.duration,
                        )
                    }
            }
        }

    /** Everything the anchor picker/scrubber sheets need, bundled to keep [state]'s combine within arity. */
    private data class PickerData(
        val worldBooks: List<ComposerWorldBook>,
        val worldBookChapters: Map<String, List<Chapter>>,
        val playheadSnapshot: AnchorSelection.Playhead?,
        val playheadLabel: AnchorLabel?,
        val endOfChapterOption: AnchorSelection.EndOfCurrentChapter?,
    )

    private val pickerDataFlow: Flow<PickerData> =
        combine(
            worldBooksFlow,
            worldBookChaptersFlow,
            playheadSnapshotFlow,
            playheadLabelFlow,
            endOfChapterOptionFlow,
        ) { books, bookChapters, playhead, playheadLabel, endOfChapter ->
            PickerData(books, bookChapters, playhead, playheadLabel, endOfChapter)
        }

    /** The composer sheet's current UI state. */
    val state: StateFlow<ComposerUiState> =
        combine(
            editorFlow,
            worldEntitiesFlow,
            worldBookLabelsFlow,
            anchorChaptersFlow,
            pickerDataFlow,
        ) { editor, entities, bookLabels, chapters, picker ->
            buildUiState(editor, entities, bookLabels, chapters, picker)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), ComposerUiState.empty())

    /**
     * Load the sheet for [world] — a brand-new note (optionally pre-mentioning
     * [prefillMentionEntityId]) when [editEventId] is null, or an existing event's edit state
     * otherwise. Safe to call once per sheet lifetime.
     */
    fun start(
        world: WorldRef,
        prefillMentionEntityId: String? = null,
        editEventId: String? = null,
    ) {
        worldFlow.value = world
        viewModelScope.launch {
            val entities = worldEntitiesFlow.first()
            val nameFor: (String) -> String? = { id -> entities.find { it.id == id }?.name }
            editorFlow.value =
                if (editEventId != null) {
                    loadForEdit(editEventId, nameFor)
                } else {
                    loadForCreate(world, prefillMentionEntityId, nameFor)
                }
        }
    }

    private suspend fun loadForEdit(
        editEventId: String,
        nameFor: (String) -> String?,
    ): EditorState {
        val event = worldEventEditRepository.observeEvent(editEventId).filterNotNull().first()
        val storedAssertion =
            event.subjectEntityId?.let { subjectId -> Assertion(event.type, subjectId, event.objectEntityId) }
        return EditorState.initial().copy(
            document = ComposerDocument.fromRaw(event.text, nameFor),
            anchor = anchorFromEvent(event.bookId, event.positionMs),
            storedAssertion = storedAssertion,
            usingStoredAssertion = true,
            isEditMode = true,
            editEventId = editEventId,
        )
    }

    private suspend fun loadForCreate(
        world: WorldRef,
        prefillMentionEntityId: String?,
        nameFor: (String) -> String?,
    ): EditorState {
        val document =
            if (prefillMentionEntityId != null) {
                val name = nameFor(prefillMentionEntityId) ?: prefillMentionEntityId
                ComposerDocument.empty().insertMention(prefillMentionEntityId, name)
            } else {
                ComposerDocument.empty()
            }
        return EditorState.initial().copy(document = document, anchor = computeDefaultAnchor(world))
    }

    private suspend fun computeDefaultAnchor(world: WorldRef): AnchorSelection {
        val bookId = playbackManager.currentBookId.value?.value ?: return AnchorSelection.AlwaysVisible
        val positionMs = playbackManager.currentPositionMs.value
        val isWorldBook =
            if (world.seriesId != null) {
                bookId in seriesRepository.getBookIdsForSeries(world.seriesId)
            } else {
                bookId == world.bookId
            }
        return if (isWorldBook) AnchorSelection.Playhead(bookId, positionMs) else AnchorSelection.AlwaysVisible
    }

    /** Reconcile a raw text-field edit — see [ComposerDocument.applyDisplayChange]. */
    fun onDisplayChanged(
        newDisplay: String,
        newCursor: Int,
    ) {
        updateDocument(editorFlow.value.document.applyDisplayChange(newDisplay, newCursor))
    }

    /** Commit a mention suggestion at the open trigger (or at the cursor with none open). */
    fun acceptMention(entity: EntityCard) {
        updateDocument(editorFlow.value.document.insertMention(entity.id, entity.name))
    }

    /** Commit a verb-phrase suggestion, replacing the open `*` trigger with plain prose. */
    fun acceptVerb(phrase: String) {
        val document = editorFlow.value.document
        val trigger = document.activeTrigger() ?: return
        val display = document.displayText()
        val newDisplay = display.substring(0, trigger.startOffset) + phrase + " " + display.substring(document.cursor)
        val newCursor = trigger.startOffset + phrase.length + 1
        updateDocument(document.applyDisplayChange(newDisplay, newCursor))
    }

    /** Mint a brand-new entity from the open mention trigger's query and insert it as a mention. */
    fun quickCreate(
        name: String,
        kind: EntityKind,
    ) {
        val world = worldFlow.value ?: return
        viewModelScope.launch {
            val result =
                entityEditRepository.createEntity(
                    kind = kind,
                    name = name,
                    homeSeriesId = world.seriesId,
                    homeBookId = world.bookId,
                )
            when (result) {
                is AppResult.Success -> updateDocument(editorFlow.value.document.insertMention(result.data, name))
                is AppResult.Failure -> errorBus.emit(result.error)
            }
        }
    }

    /** Strip the detected assertion from the note — it saves as a plain [WorldEventType.NOTE]. */
    fun dismissAssertion() {
        editorFlow.update { current ->
            current.copy(dismissed = true, lastDismissedAssertion = current.currentAssertion())
        }
    }

    /** Change the note's visibility anchor. */
    fun selectAnchor(selection: AnchorSelection) {
        editorFlow.update { it.copy(anchor = selection) }
    }

    /**
     * Resolves the [AnchorLabel] the position scrubber sheet's confirm bar shows while the author
     * is still dragging — computed from [state]'s already-loaded [ComposerUiState.worldBooks] /
     * [ComposerUiState.worldBookChapters] without touching [selectAnchor]'s committed anchor. The
     * scrubber sheet holds its in-progress `(bookId, positionMs)` as sheet-local UI state (not
     * routed through the VM per drag), so this is a synchronous read, not a [Flow].
     */
    fun previewAnchorLabel(
        bookId: String,
        positionMs: Long,
    ): AnchorLabel {
        val current = state.value
        val bookLabel = current.worldBooks.firstOrNull { it.id == bookId }?.let { it.sequenceLabel ?: it.title }
        val chapters = current.worldBookChapters[bookId].orEmpty()
        return AnchorLabeler.label(bookLabel, chapters, positionMs)
    }

    /** Record (create mode) or update (edit mode) the note. No-ops while a save is already in flight. */
    fun save() {
        val editor = editorFlow.value
        val world = worldFlow.value
        if (editor.saving || world == null) return
        editorFlow.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = submit(editor, world)
            editorFlow.update { it.copy(saving = false) }
            when (result) {
                is AppResult.Success -> savedEvents.trySend(WorldComposerEvent.Saved)
                is AppResult.Failure -> errorBus.emit(result.error)
            }
        }
    }

    private suspend fun submit(
        editor: EditorState,
        world: WorldRef,
    ): AppResult<Unit> {
        val effectiveAssertion = if (editor.dismissed) null else editor.currentAssertion()
        val (bookId, positionMs) = editor.anchor.toAnchorPair()
        val text = editor.document.rawText()
        val type = effectiveAssertion?.type ?: WorldEventType.NOTE

        return if (editor.editEventId != null) {
            worldEventEditRepository.update(
                WorldEventUpsert(
                    id = editor.editEventId,
                    homeSeriesId = world.seriesId,
                    homeBookId = world.bookId,
                    bookId = bookId,
                    positionMs = positionMs,
                    type = type,
                    text = text,
                    subjectEntityId = effectiveAssertion?.subjectEntityId,
                    objectEntityId = effectiveAssertion?.objectEntityId,
                ),
            )
        } else {
            worldEventEditRepository
                .record(
                    NewWorldEvent(
                        type = type,
                        text = text,
                        homeSeriesId = world.seriesId,
                        homeBookId = world.bookId,
                        bookId = bookId,
                        positionMs = positionMs,
                        subjectEntityId = effectiveAssertion?.subjectEntityId,
                        objectEntityId = effectiveAssertion?.objectEntityId,
                    ),
                ).map { }
        }
    }

    /**
     * Applies a document mutation: any edit flips [EditorState.usingStoredAssertion] off (the
     * stored-columns chip is only ever shown before the first edit — see the class KDoc), and
     * clears [EditorState.dismissed] if the freshly re-parsed assertion no longer matches the one
     * that was dismissed.
     */
    private fun updateDocument(newDocument: ComposerDocument) {
        editorFlow.update { current ->
            val newAssertion = AssertionParser.parse(newDocument.segments)
            val stillMatchesDismissed = current.dismissed && newAssertion == current.lastDismissedAssertion
            current.copy(
                document = newDocument,
                usingStoredAssertion = false,
                dismissed = stillMatchesDismissed,
                lastDismissedAssertion = if (stillMatchesDismissed) current.lastDismissedAssertion else null,
            )
        }
    }

    /** The assertion currently in effect — stored columns before the first edit, else live-parsed. */
    private fun EditorState.currentAssertion(): Assertion? =
        if (usingStoredAssertion) storedAssertion else AssertionParser.parse(document.segments)

    /** This world's books in reading order — see [ComposerUiState.worldBooks]. */
    private fun observeWorldBooks(world: WorldRef): Flow<List<ComposerWorldBook>> =
        if (world.seriesId != null) {
            seriesRepository.observeSeriesWithBooks(world.seriesId).map { seriesWithBooks ->
                seriesWithBooks
                    ?.booksSortedBySequence()
                    ?.map { book ->
                        ComposerWorldBook(
                            id = book.id.value,
                            title = book.title,
                            sequenceLabel = seriesWithBooks.sequenceFor(book.id.value),
                            durationMs = book.duration,
                        )
                    }.orEmpty()
            }
        } else {
            bookRepository.observeBookListItems(listOf(world.bookId!!)).map { books ->
                books.map { book ->
                    ComposerWorldBook(
                        id = book.id.value,
                        title = book.title,
                        sequenceLabel = null,
                        durationMs = book.duration,
                    )
                }
            }
        }

    private fun buildUiState(
        editor: EditorState,
        entities: List<Entity>,
        bookLabels: Map<String, String>,
        chapters: List<Chapter>,
        picker: PickerData,
    ): ComposerUiState {
        val document = editor.document
        val trigger = document.activeTrigger()
        val currentAssertion = editor.currentAssertion()
        val (anchorBookId, anchorPositionMs) = editor.anchor.toAnchorPair()

        return ComposerUiState(
            displayText = document.displayText(),
            cursor = document.cursor,
            mentionSpans = document.mentionSpans(),
            suggestions = mentionSuggestions(trigger, entities),
            verbSuggestions = verbSuggestions(trigger),
            showQuickCreate = shouldShowQuickCreate(trigger, entities),
            quickCreateQuery = if (trigger != null && trigger.kind == TriggerKind.MENTION) trigger.query else "",
            assertion = if (editor.dismissed) null else currentAssertion?.toUi(entities),
            anchor = editor.anchor,
            anchorSummary = AnchorLabeler.label(anchorBookId?.let { bookLabels[it] }, chapters, anchorPositionMs),
            canSave = (currentAssertion != null && !editor.dismissed) || document.displayText().isNotBlank(),
            worldBooks = picker.worldBooks,
            worldBookChapters = picker.worldBookChapters,
            playheadSnapshot = picker.playheadSnapshot,
            playheadLabel = picker.playheadLabel,
            endOfChapterOption = picker.endOfChapterOption,
            isEditMode = editor.isEditMode,
        )
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}

private fun anchorFromEvent(
    bookId: String?,
    positionMs: Long?,
): AnchorSelection =
    if (bookId == null) {
        AnchorSelection.AlwaysVisible
    } else {
        AnchorSelection.Custom(bookId, positionMs ?: 0L)
    }

/** `(bookId, positionMs)` this anchor resolves to for a [NewWorldEvent]/[WorldEventUpsert] write. */
private fun AnchorSelection.toAnchorPair(): Pair<String?, Long?> =
    when (this) {
        is AnchorSelection.Playhead -> bookId to positionMs
        is AnchorSelection.BeginningOfBook -> bookId to 0L
        is AnchorSelection.EndOfCurrentChapter -> bookId to positionMs
        is AnchorSelection.Custom -> bookId to positionMs
        AnchorSelection.AlwaysVisible -> null to null
    }

private fun Assertion.toUi(entities: List<Entity>): AssertionUi {
    val entityById = entities.associateBy { it.id }
    return AssertionUi(
        type = type,
        subjectName = entityById[subjectEntityId]?.name ?: subjectEntityId,
        objectName = objectEntityId?.let { entityById[it]?.name ?: it },
    )
}

private fun Entity.toCard(): EntityCard = EntityCard(id = id, name = name, kind = kind)

private fun mentionSuggestions(
    trigger: Trigger?,
    entities: List<Entity>,
): List<EntityCard> {
    if (trigger == null || trigger.kind != TriggerKind.MENTION) return emptyList()
    return entities.filter { it.name.contains(trigger.query, ignoreCase = true) }.map(Entity::toCard)
}

private fun verbSuggestions(trigger: Trigger?): List<String> {
    if (trigger == null || trigger.kind != TriggerKind.VERB) return emptyList()
    return VERB_PHRASES.filter { it.startsWith(trigger.query, ignoreCase = true) }
}

private fun shouldShowQuickCreate(
    trigger: Trigger?,
    entities: List<Entity>,
): Boolean {
    if (trigger == null || trigger.kind != TriggerKind.MENTION || trigger.query.isBlank()) return false
    return entities.none { it.name.equals(trigger.query, ignoreCase = true) }
}
