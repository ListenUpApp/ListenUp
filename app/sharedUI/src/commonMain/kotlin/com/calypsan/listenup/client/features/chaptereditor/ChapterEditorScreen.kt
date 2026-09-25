package com.calypsan.listenup.client.features.chaptereditor

import com.calypsan.listenup.client.domain.model.Chapter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.util.PlatformBackHandler
import com.calypsan.listenup.client.presentation.chaptereditor.timeline.TimelineFileBoundary
import com.calypsan.listenup.client.presentation.chaptereditor.timeline.TimelineChapter
import com.calypsan.listenup.client.presentation.chaptereditor.timeline.TimelineLane
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorEvent
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorUiState
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorViewModel
import com.calypsan.listenup.client.presentation.chaptereditor.DriftPreview
import com.calypsan.listenup.core.BookId
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.chapter_editor_done
import listenup.composeapp.generated.resources.chapter_editor_drift_title
import listenup.composeapp.generated.resources.chapter_editor_new_chapter_title
import listenup.composeapp.generated.resources.chapter_editor_saving
import listenup.composeapp.generated.resources.chapter_editor_subtitle
import listenup.composeapp.generated.resources.chapter_editor_title
import listenup.composeapp.generated.resources.chapter_editor_undo
import listenup.composeapp.generated.resources.chapter_editor_unsaved
import listenup.composeapp.generated.resources.common_back
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The chapter editor, bound to its ViewModel.
 *
 * Everything here is either navigation, transport, or a dialog — the editing itself lives in the
 * ViewModel, which is why iOS and web can reach the same behaviour without reimplementing it.
 *
 * Two things this screen is careful about. The playhead is only shown when the book being edited
 * is the book actually loaded in the player: a position borrowed from a different book would make
 * "set start at playhead" write a number from somewhere else entirely. And a dirty draft is never
 * dropped silently — the editor holds the only copy of the user's work until they save.
 *
 * @param bookId the book whose chapters are being edited.
 * @param onBack leave the editor.
 * @param viewModel scoped to [bookId]; a fresh one per book, never switched.
 * @param playbackManager transport state — whether this book is loaded, and where its playhead is.
 *   The editor only moves playback on an explicit "Play from here", through the ViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterEditorScreen(
    bookId: String,
    onBack: () -> Unit,
    viewModel: ChapterEditorViewModel = koinViewModel(parameters = { parametersOf(bookId) }),
    playbackManager: PlaybackManager = koinInject(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val timeline by playbackManager.currentTimeline.collectAsStateWithLifecycle()
    val positionMs by playbackManager.currentPositionMs.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Only this book's transport counts. Anything else and the playhead is not about what is on
    // screen, so it is absent rather than misleading.
    val isThisBookLoaded = timeline?.bookId == BookId(bookId)
    val playheadMs = if (isThisBookLoaded) positionMs else null

    var pendingDiscard by remember { mutableStateOf(false) }
    var rowAction by remember { mutableStateOf<RowAction?>(null) }
    var lane by remember { mutableStateOf<TimelineLane?>(null) }
    var query by remember { mutableStateOf("") }

    val editing = state as? ChapterEditorUiState.Editing
    // From the shared state, which already knows whether this book is the one in the player.
    val fileBoundaries = editing?.fileBoundaries.orEmpty()
    val isDirty = editing?.isDirty == true
    val leave = { if (isDirty) pendingDiscard = true else onBack() }

    val newChapterTitle = stringResource(Res.string.chapter_editor_new_chapter_title)

    // The toolbar arrow is not the only way out. Without this the system back gesture pops the
    // screen straight past the confirmation, and the draft — the only copy of the work — is gone.
    PlatformBackHandler(enabled = isDirty) { pendingDiscard = true }

    // Open where the listener already is. A 65-hour book opened at 0:00 is technically correct and
    // useless; when this book is the one loaded, the interesting boundary is the one being heard.
    LaunchedEffect(isThisBookLoaded) {
        if (isThisBookLoaded) {
            // Not yet opened: the lane opens around the playhead on its own (TimelineLane.opening).
            lane = lane?.let { current -> editing?.let { current.centredOn(positionMs, it.bookDurationMs) } ?: current }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ChapterEditorEvent.Saved -> {
                    onBack()
                }

                // SaveFailed already reaches the user through the global error bus; repeating it
                // here would show the same failure twice.
                is ChapterEditorEvent.SaveFailed -> {}

                is ChapterEditorEvent.Invalid -> {
                    // Read through the ViewModel rather than the captured composition value, so the
                    // number in the message describes the set that was actually refused.
                    val chapters = (viewModel.state.value as? ChapterEditorUiState.Editing)?.chapters.orEmpty()
                    chapterProblemMessage(event.problems, chapters)?.let { snackbarHostState.showSnackbar(it) }
                }
            }
        }
    }

    ListenUpScaffold(
        topBar = {
            TopAppBar(
                title = { EditorTitle(editing) },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.common_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = viewModel::beginDrift,
                        // Nothing to interpolate between on an empty or single-chapter book.
                        enabled = editing != null && editing.chapters.size > 1 && editing.drift == null,
                    ) {
                        Icon(
                            Icons.Outlined.Timeline,
                            stringResource(Res.string.chapter_editor_drift_title),
                        )
                    }
                    IconButton(onClick = viewModel::undo, enabled = editing?.canUndo == true) {
                        Icon(Icons.AutoMirrored.Filled.Undo, stringResource(Res.string.chapter_editor_undo))
                    }
                    EditorStatus(editing)
                    TextButton(
                        onClick = { if (isDirty) viewModel.save() else onBack() },
                        enabled = editing != null && !editing.isSaving,
                    ) {
                        Text(stringResource(Res.string.chapter_editor_done))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        ChapterEditorBody(
            state = state,
            padding = padding,
            playheadMs = playheadMs,
            fileBoundaries = fileBoundaries,
            lane = lane,
            onLaneChange = { lane = it },
            query = query,
            onQueryChange = { query = it },
            onPinAnchor = {
                val selected = editing?.selectedChapterId
                val at = playheadMs
                if (selected != null && at != null) viewModel.pinAnchor(selected, at)
            },
            newChapterTitle = newChapterTitle,
            viewModel = viewModel,
            onMore = { rowAction = RowAction.Choosing(it) },
            onEditTime = { rowAction = RowAction.EditingTime(it) },
        )
    }

    if (pendingDiscard) {
        DiscardChapterEditsDialog(
            onDiscard = {
                pendingDiscard = false
                viewModel.resetToSource()
                onBack()
            },
            onDismiss = { pendingDiscard = false },
        )
    }

    RowActionDialogs(
        action = rowAction,
        chapterOf = { id -> editing?.chapters?.firstOrNull { it.id == id } },
        canPlayFromHere = isThisBookLoaded,
        onAction = { rowAction = it },
        onRename = viewModel::retitle,
        onRetime = viewModel::retime,
        onInsertBelow = { id -> viewModel.insertBelow(id, newChapterTitle) },
        onPlayFromHere = viewModel::playFrom,
        onDelete = viewModel::remove,
    )
}

/** Which of a row's overflow dialogs is open, if any. */
private sealed interface RowAction {
    val chapterId: String

    /** The overflow itself — rename, insert below, play from here, delete. */
    data class Choosing(
        override val chapterId: String,
    ) : RowAction

    /** Editing the title. */
    data class Renaming(
        override val chapterId: String,
    ) : RowAction

    /** Confirming removal. */
    data class Deleting(
        override val chapterId: String,
    ) : RowAction

    /** Typing the start exactly. */
    data class EditingTime(
        override val chapterId: String,
    ) : RowAction
}

/**
 * The row overflow and everything it leads to.
 *
 * One nullable value rather than three booleans, so "renaming and deleting at once" is not a state
 * that can be reached — the dialogs are steps in a sequence, and the type says so.
 */
@Composable
private fun RowActionDialogs(
    action: RowAction?,
    chapterOf: (String) -> Chapter?,
    canPlayFromHere: Boolean,
    onAction: (RowAction?) -> Unit,
    onRename: (String, String) -> Unit,
    onRetime: (String, Long) -> Unit,
    onInsertBelow: (String) -> Unit,
    onPlayFromHere: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    when (action) {
        null -> {}

        is RowAction.Choosing -> {
            ChapterActionsDialog(
                onRename = { onAction(RowAction.Renaming(action.chapterId)) },
                onInsertBelow = {
                    onInsertBelow(action.chapterId)
                    onAction(null)
                },
                onPlayFromHere =
                    if (canPlayFromHere) {
                        {
                            onPlayFromHere(action.chapterId)
                            onAction(null)
                        }
                    } else {
                        null
                    },
                onDelete = { onAction(RowAction.Deleting(action.chapterId)) },
                onDismiss = { onAction(null) },
            )
        }

        is RowAction.EditingTime -> {
            ChapterTimeDialog(
                initialMs = chapterOf(action.chapterId)?.startTime ?: 0L,
                onConfirm = {
                    onRetime(action.chapterId, it)
                    onAction(null)
                },
                onDismiss = { onAction(null) },
            )
        }

        is RowAction.Renaming -> {
            RenameChapterDialog(
                initialTitle = chapterOf(action.chapterId)?.title.orEmpty(),
                onConfirm = {
                    onRename(action.chapterId, it)
                    onAction(null)
                },
                onDismiss = { onAction(null) },
            )
        }

        is RowAction.Deleting -> {
            DeleteChapterDialog(
                onConfirm = {
                    onDelete(action.chapterId)
                    onAction(null)
                },
                onDismiss = { onAction(null) },
            )
        }
    }
}

/**
 * What fills the scaffold: a spinner, a message, the empty state, or the editor itself.
 *
 * Split from [ChapterEditorScreen] so that function stays about wiring — transport, events,
 * dialogs — and this one stays about what is on screen.
 */
@Composable
private fun ChapterEditorBody(
    state: ChapterEditorUiState,
    padding: PaddingValues,
    playheadMs: Long?,
    fileBoundaries: List<TimelineFileBoundary>,
    lane: TimelineLane?,
    onLaneChange: (TimelineLane) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onPinAnchor: () -> Unit,
    newChapterTitle: String,
    viewModel: ChapterEditorViewModel,
    onMore: (String) -> Unit,
    onEditTime: (String) -> Unit,
) {
    when (state) {
        ChapterEditorUiState.Loading -> {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { ListenUpLoadingIndicator() }
        }

        is ChapterEditorUiState.Error -> {
            Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { Text(state.message) }
        }

        is ChapterEditorUiState.Editing -> {
            if (state.isEmpty) {
                ChapterEditorEmptyState(
                    onAddFirst = { viewModel.addAt(playheadMs ?: 0L, newChapterTitle) },
                    onLookUp = {},
                    modifier = Modifier.padding(padding),
                    // Lookup is not built yet. Offering a button that does nothing would be a
                    // worse dead end than the manual route the spec makes primary.
                    canLookUp = false,
                )
            } else {
                val currentLane = lane ?: TimelineLane.opening(state.bookDurationMs, playheadMs, widthPx = 0f)
                Column(Modifier.padding(padding)) {
                    if (state.changedElsewhere) {
                        ChangedElsewhereBanner(Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                    state.drift?.let { drift ->
                        DriftSheet(
                            drift = drift,
                            chapters = state.chapters,
                            hasSelection = state.selectedChapterId != null,
                            hasPlayhead = playheadMs != null,
                            onPin = onPinAnchor,
                            onApply = viewModel::applyDrift,
                            onCancel = viewModel::cancelDrift,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    ChapterEditorContent(
                        chapters = state.chapters.numbered(),
                        bookDurationMs = state.bookDurationMs,
                        lane = currentLane,
                        onLaneChange = onLaneChange,
                        isWide =
                            currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
                                WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND,
                            ),
                        selectedChapterId = state.selectedChapterId,
                        playheadMs = playheadMs,
                        onSelect = viewModel::select,
                        // The row already speaks milliseconds (COARSE_NUDGE_MS); nothing scales it here.
                        onNudge = viewModel::nudge,
                        onAddAtPlayhead = { viewModel.addAt(playheadMs ?: 0L, newChapterTitle) },
                        onSnapToPlayhead = { id -> playheadMs?.let { viewModel.snapToPlayhead(id, it) } },
                        onToggleLock = viewModel::toggleLock,
                        onMore = onMore,
                        onEditTime = onEditTime,
                        fileBoundaries = fileBoundaries,
                        // The corrected positions, drawn beside the current ones. This is the
                        // parameter the lane has always accepted and nothing ever supplied.
                        ghosts = driftGhosts(state),
                        lockedChapterIds = state.lockedChapterIds,
                        query = query,
                        onQueryChange = onQueryChange,
                        // Clamped between neighbours by the ViewModel, so a drag can never push a
                        // boundary past the one beside it however far the finger travels.
                        onRetime = viewModel::retime,
                    )
                }
            }
        }
    }
}

/**
 * The previewed positions as lane markers, or nothing when there is no proposal to show.
 *
 * Numbered by position in the corrected set, so a ghost carries the number the chapter *would*
 * have — which is the whole question when a correction re-sorts boundaries.
 */
private fun driftGhosts(state: ChapterEditorUiState.Editing): List<TimelineChapter> {
    val ready = state.drift?.preview as? DriftPreview.Ready ?: return emptyList()
    return ready.corrected.mapIndexed { index, chapter ->
        TimelineChapter(id = chapter.id, number = index + 1, startMs = chapter.startTime)
    }
}

@Composable
private fun EditorTitle(editing: ChapterEditorUiState.Editing?) {
    Column {
        Text(stringResource(Res.string.chapter_editor_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (editing != null) {
            Text(
                stringResource(Res.string.chapter_editor_subtitle, editing.bookTitle, editing.chapters.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun EditorStatus(editing: ChapterEditorUiState.Editing?) {
    // Nothing at all when the set is clean: a book just opened has not been "Saved", and saying so
    // would be the screen's only untrue statement.
    val label =
        when {
            editing == null -> null
            editing.isSaving -> stringResource(Res.string.chapter_editor_saving)
            editing.isDirty -> stringResource(Res.string.chapter_editor_unsaved)
            else -> null
        }
    label?.let {
        Text(
            it,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
