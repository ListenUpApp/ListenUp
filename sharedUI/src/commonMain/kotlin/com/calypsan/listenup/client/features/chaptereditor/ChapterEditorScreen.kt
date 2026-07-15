package com.calypsan.listenup.client.features.chaptereditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.design.TwoPaneMinWidth
import com.calypsan.listenup.client.design.components.ListenUpDestructiveDialog
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicatorSmall
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.design.components.LocalSnackbarHostState
import com.calypsan.listenup.client.design.reorderable.ReorderNode
import com.calypsan.listenup.client.design.reorderable.ReorderableList
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.timeline.LanePolicy
import com.calypsan.listenup.client.design.timeline.MarkerLane
import com.calypsan.listenup.client.design.timeline.MarkerLaneTimeline
import com.calypsan.listenup.client.design.timeline.MarkerStyle
import com.calypsan.listenup.client.design.timeline.TimeMarker
import com.calypsan.listenup.client.domain.chapter.groupChapters
import com.calypsan.listenup.client.domain.model.AudioFile
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.features.chaptereditor.components.ChapterDetailPanel
import com.calypsan.listenup.client.features.chaptereditor.components.OfflineSaveSheet
import com.calypsan.listenup.client.features.chaptereditor.components.TierChipRow
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorEvent
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorUiState
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorViewModel
import com.calypsan.listenup.client.presentation.chaptereditor.TierKind
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.core.DurationFormatter
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.domain.TierLabels
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.time.Duration.Companion.milliseconds
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.chapter_editor_changed_elsewhere_message
import listenup.composeapp.generated.resources.chapter_editor_changed_elsewhere_refresh
import listenup.composeapp.generated.resources.chapter_editor_chapter_row
import listenup.composeapp.generated.resources.chapter_editor_detail_panel_empty
import listenup.composeapp.generated.resources.chapter_editor_drift_entry_point
import listenup.composeapp.generated.resources.chapter_editor_lens_structure
import listenup.composeapp.generated.resources.chapter_editor_lens_timing
import listenup.composeapp.generated.resources.chapter_editor_preview_show_offline_sheet
import listenup.composeapp.generated.resources.chapter_editor_reset
import listenup.composeapp.generated.resources.chapter_editor_reset_body
import listenup.composeapp.generated.resources.chapter_editor_reset_confirm
import listenup.composeapp.generated.resources.chapter_editor_reset_title
import listenup.composeapp.generated.resources.chapter_editor_save
import listenup.composeapp.generated.resources.chapter_editor_saved
import listenup.composeapp.generated.resources.chapter_editor_saving
import listenup.composeapp.generated.resources.chapter_editor_tier_book_default
import listenup.composeapp.generated.resources.chapter_editor_tier_part_default
import listenup.composeapp.generated.resources.chapter_editor_title
import listenup.composeapp.generated.resources.chapter_editor_undo
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.common_cancel
import listenup.composeapp.generated.resources.common_dismiss
import listenup.composeapp.generated.resources.common_retry

/** The Structure/Timing view-mode toggle — a local view mode, never a nav route. */
private enum class Lens { STRUCTURE, TIMING }

/** Docked detail-panel width at/above [TwoPaneMinWidth]. */
private val DetailPanelWidth = 380.dp

private const val CHAPTER_STYLE_KEY = "chapter"
private const val FILE_BOUNDARY_STYLE_KEY = "fileBoundary"

/**
 * [TimeMarker.styleKey][com.calypsan.listenup.client.design.timeline.TimeMarker.styleKey] tag for
 * [DriftCorrectionSheet]'s ghost-preview markers. `MarkerLaneTimeline`'s ghost renderer currently
 * hardcodes [MaterialTheme.colorScheme.tertiary] regardless of style lookup, but the entry below
 * keeps [chapterEditorMarkerStyles] a complete map of every `styleKey` this screen emits.
 */
private const val GHOST_STYLE_KEY = "ghost"

/**
 * The unified Structure/Timing chapter editor screen. Stateful entry point — resolves its
 * [ChapterEditorViewModel] via Koin (scoped per-[bookId]) and renders the ViewModel's sealed
 * [ChapterEditorUiState].
 *
 * Nav wiring (route + a BookDetail entry point) is out of scope here — the caller supplies
 * [bookId] and [onBackClick] directly.
 */
@Composable
fun ChapterEditorScreen(
    bookId: String,
    onBackClick: () -> Unit,
    viewModel: ChapterEditorViewModel = koinViewModel { parametersOf(bookId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        when (val s = state) {
            is ChapterEditorUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    ListenUpLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                }
            }

            is ChapterEditorUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = s.message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = Spacing.screenMargin),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            is ChapterEditorUiState.Editing -> {
                ChapterEditorEditingContent(
                    bookId = bookId,
                    state = s,
                    viewModel = viewModel,
                    onBackClick = onBackClick,
                )
            }
        }
    }
}

/**
 * Ready-state host: owns screen-scoped local state (lens, selection, dialogs) and lays out the
 * width-adaptive shell. Single column with a bottom-sheet detail panel below [TwoPaneMinWidth];
 * a permanently docked right panel at/above it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
@Composable
private fun ChapterEditorEditingContent(
    bookId: String,
    state: ChapterEditorUiState.Editing,
    viewModel: ChapterEditorViewModel,
    onBackClick: () -> Unit,
) {
    var lens by remember { mutableStateOf(Lens.TIMING) }
    var selectedChapterId by remember { mutableStateOf<String?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var showOfflineSheet by remember { mutableStateOf(false) }
    var showDriftSheet by remember { mutableStateOf(false) }
    var driftGhosts by remember { mutableStateOf<List<TimeMarker>?>(null) }
    var saveFailedError by remember { mutableStateOf<AppError?>(null) }

    val snackbarHostState = LocalSnackbarHostState.current
    val savedLabel = stringResource(Res.string.chapter_editor_saved)
    val scope = rememberCoroutineScope()
    val playbackManager: PlaybackManager = koinInject()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ChapterEditorEvent.SavedSuccessfully -> {
                    saveFailedError = null
                    scope.launch { snackbarHostState.showSnackbar(savedLabel) }
                }

                is ChapterEditorEvent.SaveFailed -> {
                    saveFailedError = event.error
                }

                is ChapterEditorEvent.OfflineBlocked -> {
                    showOfflineSheet = true
                }
            }
        }
    }

    // A deleted chapter (or an undo/reset that moved past it) must never leave the detail panel
    // rendering stale data — deselect the instant it stops existing in the draft.
    LaunchedEffect(state.draft, selectedChapterId) {
        if (selectedChapterId != null && state.draft.none { it.id == selectedChapterId }) {
            selectedChapterId = null
        }
    }

    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val isWide = windowSizeClass.isWidthAtLeastBreakpoint(TwoPaneMinWidth.value.toInt())
    val selectedChapter = state.draft.firstOrNull { it.id == selectedChapterId }

    // Deferred-lambda playhead read (the PlayerScrubber idiom): only valid while this book is the
    // one actually loaded in the player, else the Snap-to-playhead action would silently retime
    // against whatever other book happens to be playing.
    val playheadMs: () -> Long =
        remember(bookId, playbackManager) {
            {
                if (playbackManager.currentTimeline.value?.bookId == BookId(bookId)) {
                    playbackManager.currentPositionMs.value
                } else {
                    0L
                }
            }
        }

    ListenUpScaffold(
        topBar = {
            ChapterEditorTopBar(
                canUndo = state.canUndo,
                isDirty = state.isDirty,
                isSaving = state.isSaving,
                onBackClick = onBackClick,
                onUndoClick = viewModel::undo,
                onResetClick = { showResetConfirm = true },
                onSaveClick = viewModel::save,
            )
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            if (state.changedElsewhere) {
                ChangedElsewhereBanner(onRefresh = viewModel::resetToSource)
            }

            saveFailedError?.let { error ->
                SaveFailedBanner(
                    error = error,
                    onRetry = {
                        saveFailedError = null
                        viewModel.save()
                    },
                    onDismiss = { saveFailedError = null },
                )
            }

            LensToggle(
                lens = lens,
                onLensChange = { lens = it },
                modifier = Modifier.padding(horizontal = Spacing.screenMargin, vertical = 8.dp),
            )

            ChapterEditorAdaptiveBody(
                bookId = bookId,
                state = state,
                lens = lens,
                viewModel = viewModel,
                isWide = isWide,
                selectedChapterId = selectedChapterId,
                selectedChapter = selectedChapter,
                onSelectChapter = { selectedChapterId = it },
                playheadMs = playheadMs,
                driftGhosts = driftGhosts,
                onCorrectDriftClick = { showDriftSheet = true },
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (!isWide && selectedChapter != null) {
        CompactChapterDetailSheet(
            chapter = selectedChapter,
            viewModel = viewModel,
            playheadMs = playheadMs,
            onDismiss = { selectedChapterId = null },
        )
    }

    if (showDriftSheet) {
        DriftCorrectionSheet(
            draft = state.draft,
            playheadMs = playheadMs,
            onApplyDrift = viewModel::applyDrift,
            onCommitDrift = viewModel::commitDrift,
            onGhostsChange = { driftGhosts = it },
            onDismiss = { showDriftSheet = false },
        )
    }

    if (showResetConfirm) {
        ListenUpDestructiveDialog(
            onDismissRequest = { showResetConfirm = false },
            title = stringResource(Res.string.chapter_editor_reset_title),
            text = stringResource(Res.string.chapter_editor_reset_body),
            confirmText = stringResource(Res.string.chapter_editor_reset_confirm),
            onConfirm = {
                showResetConfirm = false
                viewModel.resetToSource()
            },
            dismissText = stringResource(Res.string.common_cancel),
            onDismiss = { showResetConfirm = false },
        )
    }

    if (showOfflineSheet) {
        OfflineSaveSheet(onDismiss = { showOfflineSheet = false })
    }
}

@Composable
private fun LensBody(
    bookId: String,
    draft: List<Chapter>,
    tierLabels: TierLabels,
    lens: Lens,
    viewModel: ChapterEditorViewModel,
    selectedChapterId: String?,
    onSelectChapter: (String?) -> Unit,
    driftGhosts: List<TimeMarker>?,
    onCorrectDriftClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (lens) {
        Lens.TIMING -> {
            TimingLensContent(
                bookId = bookId,
                draft = draft,
                viewModel = viewModel,
                selectedChapterId = selectedChapterId,
                onSelectChapter = onSelectChapter,
                driftGhosts = driftGhosts,
                onCorrectDriftClick = onCorrectDriftClick,
                modifier = modifier,
            )
        }

        Lens.STRUCTURE -> {
            StructureLensContent(
                draft = draft,
                tierLabels = tierLabels,
                viewModel = viewModel,
                selectedChapterId = selectedChapterId,
                onSelectChapter = onSelectChapter,
                modifier = modifier,
            )
        }
    }
}

/**
 * Width-adaptive editor body: two-pane (active lens + docked detail panel) at/above
 * [TwoPaneMinWidth], a single lens column below it (its detail panel is a bottom sheet the caller
 * hosts). Extracted from [ChapterEditorEditingContent] to keep that host's cognitive complexity in
 * bounds — this owns the whole width fork and its nested docked-vs-empty panel branch.
 */
@Composable
private fun ChapterEditorAdaptiveBody(
    bookId: String,
    state: ChapterEditorUiState.Editing,
    lens: Lens,
    viewModel: ChapterEditorViewModel,
    isWide: Boolean,
    selectedChapterId: String?,
    selectedChapter: Chapter?,
    onSelectChapter: (String?) -> Unit,
    playheadMs: () -> Long,
    driftGhosts: List<TimeMarker>?,
    onCorrectDriftClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isWide) {
        Row(modifier = modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                LensBody(
                    bookId = bookId,
                    draft = state.draft,
                    tierLabels = state.tierLabels,
                    lens = lens,
                    viewModel = viewModel,
                    selectedChapterId = selectedChapterId,
                    onSelectChapter = onSelectChapter,
                    driftGhosts = driftGhosts,
                    onCorrectDriftClick = onCorrectDriftClick,
                )
            }
            VerticalDivider(modifier = Modifier.fillMaxHeight())
            Box(modifier = Modifier.width(DetailPanelWidth).fillMaxHeight()) {
                DockedDetailPanel(
                    chapter = selectedChapter,
                    viewModel = viewModel,
                    playheadMs = playheadMs,
                )
            }
        }
    } else {
        LensBody(
            bookId = bookId,
            draft = state.draft,
            tierLabels = state.tierLabels,
            lens = lens,
            viewModel = viewModel,
            selectedChapterId = selectedChapterId,
            onSelectChapter = onSelectChapter,
            driftGhosts = driftGhosts,
            onCorrectDriftClick = onCorrectDriftClick,
            modifier = modifier,
        )
    }
}

/** The permanently docked (wide-layout) detail panel, or a "select a chapter" placeholder when none is selected. */
@Composable
private fun DockedDetailPanel(
    chapter: Chapter?,
    viewModel: ChapterEditorViewModel,
    playheadMs: () -> Long,
) {
    if (chapter != null) {
        ChapterDetailPanel(
            chapter = chapter,
            onTitleChange = { viewModel.rename(chapter.id, it) },
            onCommitStartTime = { ms -> viewModel.retime(chapter.id, ms) },
            onSnapToPlayhead = { viewModel.retime(chapter.id, playheadMs()) },
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(Res.string.chapter_editor_detail_panel_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
    }
}

/** The compact (single-column) detail panel, hosted as a modal bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactChapterDetailSheet(
    chapter: Chapter,
    viewModel: ChapterEditorViewModel,
    playheadMs: () -> Long,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        ChapterDetailPanel(
            chapter = chapter,
            onTitleChange = { viewModel.rename(chapter.id, it) },
            onCommitStartTime = { ms -> viewModel.retime(chapter.id, ms) },
            onSnapToPlayhead = { viewModel.retime(chapter.id, playheadMs()) },
        )
    }
}

// =============================================================================
// TOP BAR
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterEditorTopBar(
    canUndo: Boolean,
    isDirty: Boolean,
    isSaving: Boolean,
    onBackClick: () -> Unit,
    onUndoClick: () -> Unit,
    onResetClick: () -> Unit,
    onSaveClick: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(Res.string.chapter_editor_title)) },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.common_back),
                )
            }
        },
        actions = {
            IconButton(onClick = onUndoClick, enabled = canUndo) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Undo,
                    contentDescription = stringResource(Res.string.chapter_editor_undo),
                )
            }
            IconButton(onClick = onResetClick) {
                Icon(
                    imageVector = Icons.Default.RestartAlt,
                    contentDescription = stringResource(Res.string.chapter_editor_reset),
                )
            }
            ChapterEditorSaveButton(isDirty = isDirty, isSaving = isSaving, onClick = onSaveClick)
            Spacer(modifier = Modifier.width(8.dp))
        },
    )
}

@Composable
private fun ChapterEditorSaveButton(
    isDirty: Boolean,
    isSaving: Boolean,
    onClick: () -> Unit,
) {
    BadgedBox(badge = { if (isDirty && !isSaving) Badge() }) {
        TextButton(onClick = onClick, enabled = isDirty && !isSaving) {
            if (isSaving) {
                ListenUpLoadingIndicatorSmall()
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(Res.string.chapter_editor_saving))
            } else {
                Text(stringResource(Res.string.chapter_editor_save))
            }
        }
    }
}

// =============================================================================
// BANNERS
// =============================================================================

/**
 * Persistent, dismissible-only-by-action banner for [ChapterEditorUiState.Editing.changedElsewhere]
 * — an incoming sync frame landed while the draft was dirty. Never auto-refreshes; [onRefresh] is
 * the one explicit "discard my edits" action (routes to [ChapterEditorViewModel.resetToSource]).
 */
@Composable
private fun ChangedElsewhereBanner(
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.screenMargin, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.chapter_editor_changed_elsewhere_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh) {
                Text(stringResource(Res.string.chapter_editor_changed_elsewhere_refresh))
            }
        }
    }
}

/** Inline retry affordance for [ChapterEditorEvent.SaveFailed] — the errorBus already raised the global snackbar. */
@Composable
private fun SaveFailedBanner(
    error: AppError,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.screenMargin, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = error.localized(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onRetry) { Text(stringResource(Res.string.common_retry)) }
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(Res.string.common_dismiss),
                )
            }
        }
    }
}

// =============================================================================
// LENS TOGGLE
// =============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LensToggle(
    lens: Lens,
    onLensChange: (Lens) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = Lens.entries
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = lens == option,
                onClick = { onLensChange(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(text = lensLabel(option))
            }
        }
    }
}

@Composable
private fun lensLabel(lens: Lens): String =
    when (lens) {
        Lens.STRUCTURE -> stringResource(Res.string.chapter_editor_lens_structure)
        Lens.TIMING -> stringResource(Res.string.chapter_editor_lens_timing)
    }

// =============================================================================
// TIMING LENS
// =============================================================================

/**
 * Timing lens: [MarkerLaneTimeline] (chapter + file-boundary lanes) above a scrollable flat list
 * of chapters, giving keyboard-precision editing a row to anchor to. Tapping a row selects the
 * chapter, opening the detail panel (docked or sheet, per the caller's width).
 */
@Composable
private fun TimingLensContent(
    bookId: String,
    draft: List<Chapter>,
    viewModel: ChapterEditorViewModel,
    selectedChapterId: String?,
    onSelectChapter: (String?) -> Unit,
    driftGhosts: List<TimeMarker>?,
    onCorrectDriftClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bookRepository: BookRepository = koinInject()
    val playbackManager: PlaybackManager = koinInject()

    val durationMs = remember(draft) { draft.maxOfOrNull { it.startTime + it.duration } ?: 0L }

    val chapterLane =
        remember(viewModel) {
            ChapterMarkerLane(
                draftFlow = viewModel.state.map { (it as? ChapterEditorUiState.Editing)?.draft.orEmpty() },
                onRetime = viewModel::retime,
            )
        }
    val fileLane =
        remember(bookId, bookRepository) {
            FileMarkerLane(
                audioFilesFlow = bookRepository.observeBookDetail(bookId).map { it?.audioFiles.orEmpty() },
            )
        }

    // Read-only for now: the write-path to issue a real seek command lives on the platform
    // AudioPlayer/MediaController, not exposed to this screen. Axis tap-to-seek is a documented
    // no-op gap (see Task 5 report); the playhead itself still renders live when this book plays.
    val playheadMs: () -> Long =
        remember(bookId, playbackManager) {
            {
                if (playbackManager.currentTimeline.value?.bookId == BookId(bookId)) {
                    playbackManager.currentPositionMs.value
                } else {
                    0L
                }
            }
        }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.screenMargin),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCorrectDriftClick) {
                Text(stringResource(Res.string.chapter_editor_drift_entry_point))
            }
        }
        MarkerLaneTimeline(
            lanes = listOf(chapterLane, fileLane),
            durationMs = durationMs,
            playheadMs = playheadMs,
            onSeek = {},
            styles = chapterEditorMarkerStyles(),
            ghosts = driftGhosts,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            itemsIndexed(draft, key = { _, chapter -> chapter.id }) { index, chapter ->
                ChapterTimingRow(
                    chapter = chapter,
                    index = index,
                    selected = chapter.id == selectedChapterId,
                    onClick = { onSelectChapter(chapter.id) },
                )
            }
        }
    }
}

@Composable
private fun chapterEditorMarkerStyles(): Map<String, MarkerStyle> =
    mapOf(
        CHAPTER_STYLE_KEY to MarkerStyle(color = MaterialTheme.colorScheme.primary, shape = CircleShape),
        FILE_BOUNDARY_STYLE_KEY to MarkerStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, shape = CircleShape),
        GHOST_STYLE_KEY to MarkerStyle(color = MaterialTheme.colorScheme.tertiary, shape = CircleShape),
    )

@Composable
private fun ChapterTimingRow(
    chapter: Chapter,
    index: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.screenMargin, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.chapter_editor_chapter_row, index + 1, chapter.title),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            Text(
                text = DurationFormatter.minutesSecondsClock(chapter.startTime.milliseconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Adapts [ChapterEditorViewModel]'s draft (a plain state, pull-shaped from the caller's point of
 * view) into the push-based [MarkerLane] contract [MarkerLaneTimeline] expects. [onRetime] is
 * wired straight to [ChapterEditorViewModel.retime] — a committed drag writes through the VM like
 * any other edit, keeping the timeline and the flat chapter list as two views of one draft.
 */
private class ChapterMarkerLane(
    draftFlow: Flow<List<Chapter>>,
    onRetime: (String, Long) -> Unit,
) : MarkerLane {
    private var latest: List<Chapter> = emptyList()

    override val policy = ChapterBoundaryPolicy(chapters = { latest }, onRetime = onRetime)

    override val markers: Flow<List<TimeMarker>> =
        draftFlow.map { chapters ->
            latest = chapters
            chapters.map { chapter ->
                TimeMarker(
                    id = chapter.id,
                    timeMs = chapter.startTime,
                    label = chapter.title,
                    styleKey = CHAPTER_STYLE_KEY,
                )
            }
        }
}

/**
 * Adapts [FileBoundaryPolicy]'s pull-based [FileBoundaryPolicy.markers] into the push-based
 * [MarkerLane] contract — a read-only reference lane, never negotiates a drag.
 */
private class FileMarkerLane(
    audioFilesFlow: Flow<List<AudioFile>>,
) : MarkerLane {
    private var latest: List<AudioFile> = emptyList()

    private val filePolicy = FileBoundaryPolicy(audioFiles = { latest })

    override val policy: LanePolicy = filePolicy

    override val markers: Flow<List<TimeMarker>> =
        audioFilesFlow.map { files ->
            latest = files
            filePolicy.markers()
        }
}

// =============================================================================
// STRUCTURE LENS
// =============================================================================

/**
 * Structure lens: the nested [ReorderableList] outline — header rows (renamable tier chip +
 * free-form section label) for titled Book/Part groups, chapter leaf rows underneath.
 */
@Composable
private fun StructureLensContent(
    draft: List<Chapter>,
    tierLabels: TierLabels,
    viewModel: ChapterEditorViewModel,
    selectedChapterId: String?,
    onSelectChapter: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nodes = remember(draft) { draft.toReorderNodes() }
    val nodesById = remember(nodes) { nodes.associateBy { it.id } }
    val headerInfoById = remember(draft) { draft.headerNodeInfos().associateBy { it.id } }
    val chaptersById = remember(draft) { draft.associateBy { it.id } }

    val bookDefault = stringResource(Res.string.chapter_editor_tier_book_default)
    val partDefault = stringResource(Res.string.chapter_editor_tier_part_default)

    ReorderableList(
        nodes = nodes,
        onMove = { move ->
            when (val edit = interpretMove(nodes, draft, move)) {
                is OutlineEdit.RelabelChapter -> {
                    viewModel.setSectionLabel(edit.chapterId, edit.partTitle, edit.bookTitle)
                }

                is OutlineEdit.Reorder -> {
                    // KNOWN GAP (Task 5): ChapterEditorViewModel exposes no bulk
                    // reorder-by-id operation, so a header-group move (reordering whole
                    // Book/Part groups) negotiates visually but does not persist yet.
                    // Chapter-leaf reparenting (RelabelChapter, above) is fully wired.
                }
            }
        },
        itemContent = { nodeId ->
            val header = headerInfoById[nodeId]
            val depth = depthOf(nodeId, nodesById)
            if (header != null) {
                TierChipRow(
                    tierWord =
                        when (header.tier) {
                            TierKind.BOOK -> tierLabels.bookTierLabel ?: bookDefault
                            TierKind.PART -> tierLabels.partTierLabel ?: partDefault
                        },
                    onTierWordCommit = { newWord -> viewModel.setTierLabel(header.tier, newWord) },
                    sectionLabel = header.label,
                    onSectionLabelCommit = { newLabel ->
                        when (header.tier) {
                            TierKind.BOOK -> {
                                viewModel.setSectionLabel(header.openerChapterId, header.otherTier, newLabel)
                            }

                            TierKind.PART -> {
                                viewModel.setSectionLabel(header.openerChapterId, newLabel, header.otherTier)
                            }
                        }
                    },
                    modifier =
                        Modifier
                            .padding(
                                start = Spacing.screenMargin + (depth * 20).dp,
                                end = Spacing.screenMargin,
                                top = 12.dp,
                                bottom = 4.dp,
                            ),
                )
            } else {
                chaptersById[nodeId]?.let { chapter ->
                    ChapterOutlineLeafRow(
                        chapter = chapter,
                        depth = depth,
                        selected = chapter.id == selectedChapterId,
                        onClick = { onSelectChapter(chapter.id) },
                    )
                }
            }
        },
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    )
}

@Composable
private fun ChapterOutlineLeafRow(
    chapter: Chapter,
    depth: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = chapter.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.padding(
                    start = Spacing.screenMargin + (depth * 20).dp,
                    end = Spacing.screenMargin,
                    top = 12.dp,
                    bottom = 12.dp,
                ),
        )
    }
}

/** Walks [ReorderNode.parentId] links to compute a node's nesting depth (root = 0). */
private fun depthOf(
    nodeId: String,
    nodesById: Map<String, ReorderNode>,
): Int {
    var depth = 0
    var current = nodesById[nodeId]
    while (current?.parentId != null) {
        depth++
        current = nodesById[current.parentId]
    }
    return depth
}

/** A Structure-lens header row's identity, label, and the single opener [Chapter] that carries it. */
private data class HeaderNodeInfo(
    val id: String,
    val tier: TierKind,
    val label: String?,
    val openerChapterId: String,
    /** The opener chapter's OTHER tier field, preserved as-is by every [HeaderNodeInfo] edit. */
    val otherTier: String?,
)

/**
 * Builds one [HeaderNodeInfo] per titled Book/Part group in [draft], keyed by an id that mirrors
 * `ChapterOutlineAdapter.toReorderNodes()`'s file-private `bookNodeId`/`partNodeId` scheme
 * (`"book-header-$bookIndex"` / `"part-header-$bookIndex-$partIndex"`) so these ids line up with
 * the [ReorderNode] ids that function builds for the same [draft] snapshot. Duplicated rather than
 * imported because those helpers are `private` to that file — see [ChapterBoundaryPolicy]'s own
 * KDoc for the same cross-file-duplication trade-off (no shared internal-constants surface).
 *
 * Only the group's single opener [Chapter] (the one actually carrying the title) is ever touched
 * by a rename — [groupChapters] documents that every other chapter in the group belongs to it
 * purely by order, not by repeating the title on its own row.
 */
private fun List<Chapter>.headerNodeInfos(): List<HeaderNodeInfo> {
    val infos = mutableListOf<HeaderNodeInfo>()
    groupChapters().forEachIndexed { bookIndex, book ->
        if (book.title != null) {
            val opener =
                book.parts
                    .first()
                    .chapters
                    .first()
            infos +=
                HeaderNodeInfo(
                    id = "book-header-$bookIndex",
                    tier = TierKind.BOOK,
                    label = book.title,
                    openerChapterId = opener.id,
                    otherTier = opener.partTitle,
                )
        }
        book.parts.forEachIndexed { partIndex, part ->
            if (part.title != null) {
                val opener = part.chapters.first()
                infos +=
                    HeaderNodeInfo(
                        id = "part-header-$bookIndex-$partIndex",
                        tier = TierKind.PART,
                        label = part.title,
                        openerChapterId = opener.id,
                        otherTier = opener.bookTitle,
                    )
            }
        }
    }
    return infos
}

// =============================================================================
// PREVIEW GALLERY
// =============================================================================

/**
 * On-device gallery of the chapter-editor screen's mock-data-free pieces, following the
 * `TimelinePreviewGallery`/`BookDetailPreviewGallery` shape. Registered in `PreviewGalleryActivity`
 * under `--es gallery chaptereditor`.
 *
 * **Scope note (Task 5):** [StructureLensContent] and [TimingLensContent] both take a concrete
 * [ChapterEditorViewModel] (not an interface) so their edits write straight through to real VM
 * ops — there is no lightweight preview-mode VM to construct without stubbing
 * [com.calypsan.listenup.client.domain.repository.BookRepository]'s large surface, which is
 * TEST-only tooling ([dev.mokkery], `KoinApplication`) not available to a `commonMain` gallery.
 * This gallery therefore covers every VM-free piece — the top bar (baseline + saving), both
 * banners, the offline sheet, and the detail panel — but not a full Timing/Structure lens render;
 * that coverage lives in `ChapterEditorScreenRenderTest` instead.
 */
@Composable
fun ChapterEditorPreviewGallery() {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            LoadingSection()
            TopBarBaselineSection()
            TopBarSavingSection()
            ChangedElsewhereSection()
            SaveFailedSection()
            OfflineSheetSection()
            DetailPanelSection()
        }
    }
}

@Composable
private fun GalleryLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

@Composable
private fun LoadingSection() {
    GalleryLabel("Loading")
    Box(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
        ListenUpLoadingIndicator()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBarBaselineSection() {
    GalleryLabel("Top bar — clean (no undo, not dirty)")
    ChapterEditorTopBar(
        canUndo = false,
        isDirty = false,
        isSaving = false,
        onBackClick = {},
        onUndoClick = {},
        onResetClick = {},
        onSaveClick = {},
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBarSavingSection() {
    GalleryLabel("Top bar — Saving")
    ChapterEditorTopBar(
        canUndo = true,
        isDirty = true,
        isSaving = true,
        onBackClick = {},
        onUndoClick = {},
        onResetClick = {},
        onSaveClick = {},
    )
}

@Composable
private fun ChangedElsewhereSection() {
    GalleryLabel("changedElsewhere banner")
    ChangedElsewhereBanner(onRefresh = {}, modifier = Modifier.padding(horizontal = 24.dp))
}

@Composable
private fun SaveFailedSection() {
    GalleryLabel("Save-failed inline retry banner")
    SaveFailedBanner(
        error = TransportError.NetworkUnavailable(),
        onRetry = {},
        onDismiss = {},
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

@Composable
private fun OfflineSheetSection() {
    GalleryLabel("Offline save sheet")
    var showSheet by remember { mutableStateOf(false) }
    TextButton(onClick = { showSheet = true }, modifier = Modifier.padding(horizontal = 24.dp)) {
        Text(stringResource(Res.string.chapter_editor_preview_show_offline_sheet))
    }
    if (showSheet) {
        OfflineSaveSheet(onDismiss = { showSheet = false })
    }
}

@Composable
private fun DetailPanelSection() {
    GalleryLabel("Chapter detail panel")
    ChapterDetailPanel(
        chapter =
            Chapter(
                id = "preview-chapter",
                title = "The Gathering Storm",
                duration = 620_000L,
                startTime = 1_245_000L,
            ),
        onTitleChange = {},
        onCommitStartTime = {},
        onSnapToPlayhead = {},
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}
