package com.calypsan.listenup.client.features.storyworld

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.features.storyworld.components.AssertionChip
import com.calypsan.listenup.client.features.storyworld.components.MentionTextField
import com.calypsan.listenup.client.features.storyworld.components.SuggestionPopup
import com.calypsan.listenup.client.features.storyworld.components.anchorLabelText
import com.calypsan.listenup.client.presentation.storyworld.WorldRef
import com.calypsan.listenup.client.presentation.storyworld.composer.AnchorSelection
import com.calypsan.listenup.client.presentation.storyworld.composer.ComposerUiState
import com.calypsan.listenup.client.presentation.storyworld.composer.ComposerWorldBook
import com.calypsan.listenup.client.presentation.storyworld.composer.WorldComposerEvent
import com.calypsan.listenup.client.presentation.storyworld.composer.WorldComposerViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.story_world_anchor_picker_footer
import listenup.composeapp.generated.resources.story_world_anchor_visible_after
import listenup.composeapp.generated.resources.story_world_composer_edit_title
import listenup.composeapp.generated.resources.story_world_composer_placeholder
import listenup.composeapp.generated.resources.story_world_composer_save
import listenup.composeapp.generated.resources.story_world_composer_title

private val FOOTER_TEXT_SIZE = 12.5.sp

/**
 * The Story World composer — one [ModalBottomSheet] for writing a world-log entry: a mention-aware
 * text field, inline entity/verb/quick-create suggestions, the detected typed-assertion chip, and
 * the anchor row (backed by the [AnchorPickerSheet] and [PositionScrubberSheet] from a prior task,
 * hosted here as nested sheet state). Saving (or an event edit's re-save) closes the sheet via
 * [WorldComposerEvent.Saved].
 *
 * @param world The world this note belongs to.
 * @param prefillMentionEntityId When creating fresh, pre-inserts a mention of this entity (e.g.
 *   opened from an entity's own "Add entry" FAB).
 * @param editEventId When non-null, loads that event's state for editing instead of creating new.
 * @param onDismiss Dismisses the sheet — called both on an explicit dismiss and after a save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposerSheet(
    world: WorldRef,
    prefillMentionEntityId: String? = null,
    editEventId: String? = null,
    onDismiss: () -> Unit,
    viewModel: WorldComposerViewModel = koinViewModel(),
) {
    LaunchedEffect(world, prefillMentionEntityId, editEventId) {
        viewModel.start(world, prefillMentionEntityId, editEventId)
    }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                WorldComposerEvent.Saved -> onDismiss()
            }
        }
    }

    val state by viewModel.state.collectAsStateWithLifecycle()

    var showAnchorPicker by remember { mutableStateOf(false) }
    var showScrubber by remember { mutableStateOf(false) }
    var showQuickCreateSheet by remember { mutableStateOf(false) }
    var scrubberBookId by remember { mutableStateOf("") }
    var scrubberPositionMs by remember { mutableStateOf(0L) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { ComposerDragHandle() },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenMargin, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ComposerHeader(isEditMode = state.isEditMode, canSave = state.canSave, onSave = viewModel::save)

            MentionTextField(
                displayText = state.displayText,
                cursor = state.cursor,
                mentionSpans = state.mentionSpans,
                onChanged = viewModel::onDisplayChanged,
                placeholder = stringResource(Res.string.story_world_composer_placeholder),
            )

            SuggestionPopup(
                suggestions = state.suggestions,
                verbSuggestions = state.verbSuggestions,
                showQuickCreate = state.showQuickCreate,
                quickCreateQuery = state.quickCreateQuery,
                onMentionSelected = viewModel::acceptMention,
                onVerbSelected = viewModel::acceptVerb,
                onQuickCreateClick = { showQuickCreateSheet = true },
            )

            state.assertion?.let { assertion ->
                AssertionChip(assertion = assertion, onDismiss = viewModel::dismissAssertion)
            }

            AnchorSummaryRow(
                summaryText =
                    stringResource(Res.string.story_world_anchor_visible_after, anchorLabelText(state.anchorSummary)),
                onClick = { showAnchorPicker = true },
            )

            ComposerFooter()
        }
    }

    if (showAnchorPicker) {
        AnchorPickerSheet(
            current = state.anchor,
            books = state.worldBooks.map(ComposerWorldBook::toAnchorBook),
            playheadSnapshot = state.playheadSnapshot,
            endOfChapterOption = state.endOfChapterOption,
            currentSummary = anchorLabelText(state.anchorSummary),
            playheadSummary = state.playheadLabel?.let { anchorLabelText(it) },
            onSelect = { selection ->
                viewModel.selectAnchor(selection)
                showAnchorPicker = false
            },
            onChoosePosition = {
                val (seedBookId, seedPositionMs) = seedScrubber(state)
                scrubberBookId = seedBookId
                scrubberPositionMs = seedPositionMs
                showAnchorPicker = false
                showScrubber = true
            },
            onDismiss = { showAnchorPicker = false },
        )
    }

    if (showScrubber) {
        val previewLabel =
            remember(viewModel, scrubberBookId, scrubberPositionMs) {
                viewModel.previewAnchorLabel(scrubberBookId, scrubberPositionMs)
            }
        PositionScrubberSheet(
            books = state.worldBooks.map(ComposerWorldBook::toAnchorBook),
            selectedBookId = scrubberBookId,
            chapters = state.worldBookChapters[scrubberBookId].orEmpty(),
            positionMs = scrubberPositionMs,
            confirmLabel = stringResource(Res.string.story_world_anchor_visible_after, anchorLabelText(previewLabel)),
            onBookSelected = { bookId ->
                scrubberBookId = bookId
                scrubberPositionMs = 0L
            },
            onPositionChange = { positionMs -> scrubberPositionMs = positionMs },
            onConfirm = {
                viewModel.selectAnchor(AnchorSelection.Custom(scrubberBookId, scrubberPositionMs))
                showScrubber = false
            },
            onDismiss = { showScrubber = false },
        )
    }

    if (showQuickCreateSheet) {
        QuickCreateSheet(
            initialName = state.quickCreateQuery,
            onCreate = { name, kind ->
                viewModel.quickCreate(name, kind)
                showQuickCreateSheet = false
            },
            onDismiss = { showQuickCreateSheet = false },
        )
    }
}

@Composable
private fun ComposerHeader(
    isEditMode: Boolean,
    canSave: Boolean,
    onSave: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text =
                stringResource(
                    if (isEditMode) Res.string.story_world_composer_edit_title else Res.string.story_world_composer_title,
                ),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
        )
        Button(onClick = onSave, enabled = canSave) {
            Text(stringResource(Res.string.story_world_composer_save))
        }
    }
}

/** Tap target showing the note's current visibility anchor — opens [AnchorPickerSheet] on tap. */
@Composable
private fun AnchorSummaryRow(
    summaryText: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = summaryText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ComposerFooter() {
    Text(
        text = stringResource(Res.string.story_world_anchor_picker_footer),
        fontSize = FOOTER_TEXT_SIZE,
        lineHeight = FOOTER_TEXT_SIZE * 1.4f,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ComposerDragHandle() {
    Surface(
        modifier = Modifier.padding(vertical = 12.dp).width(32.dp).height(4.dp),
        shape = RoundedCornerShape(2.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
    ) {}
}

/** Maps the ViewModel's world-book projection to the anchor picker/scrubber sheets' UI-layer shape. */
private fun ComposerWorldBook.toAnchorBook(): AnchorBook =
    AnchorBook(id = id, title = title, sequenceLabel = sequenceLabel, durationMs = durationMs)

/**
 * Seeds the position scrubber's local `(bookId, positionMs)` when "Choose a specific position…" is
 * tapped: the current anchor's own book+position when it carries one, else the live playhead's,
 * else this world's first book at the very beginning.
 */
private fun seedScrubber(state: ComposerUiState): Pair<String, Long> =
    when (val anchor = state.anchor) {
        is AnchorSelection.Custom -> anchor.bookId to anchor.positionMs
        is AnchorSelection.Playhead -> anchor.bookId to anchor.positionMs
        is AnchorSelection.EndOfCurrentChapter -> anchor.bookId to anchor.positionMs
        is AnchorSelection.BeginningOfBook -> anchor.bookId to 0L
        AnchorSelection.AlwaysVisible ->
            state.playheadSnapshot?.let { it.bookId to it.positionMs }
                ?: (state.worldBooks.firstOrNull()?.id.orEmpty() to 0L)
    }
