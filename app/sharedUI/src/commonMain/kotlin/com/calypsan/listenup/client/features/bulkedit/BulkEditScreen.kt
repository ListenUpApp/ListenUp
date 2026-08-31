package com.calypsan.listenup.client.features.bulkedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.features.bookedit.components.StudioCard
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditEvent
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditUiState
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.bulk_edit_apply_none
import listenup.composeapp.generated.resources.bulk_edit_apply_one
import listenup.composeapp.generated.resources.bulk_edit_apply_plural
import listenup.composeapp.generated.resources.bulk_edit_applying
import listenup.composeapp.generated.resources.bulk_edit_card_preview
import listenup.composeapp.generated.resources.bulk_edit_card_publishing
import listenup.composeapp.generated.resources.bulk_edit_card_publishing_note
import listenup.composeapp.generated.resources.bulk_edit_some_not_loaded_one
import listenup.composeapp.generated.resources.bulk_edit_some_not_loaded_plural
import listenup.composeapp.generated.resources.bulk_edit_title_one
import listenup.composeapp.generated.resources.bulk_edit_title_plural
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Page margin for the cards; the hero owns its own. */
private val PageMargin = 16.dp
private val WidePageMargin = 24.dp
private val CardGap = 16.dp
private val LoadingPadding = 48.dp
private val NoticeIconSize = 22.dp

/** The form column earns more of a wide window than the preview does — it holds the input. */
private const val FORM_COLUMN_WEIGHT = 1.35f
private const val PREVIEW_COLUMN_WEIGHT = 1f

/**
 * Bulk metadata editing for a selection of books.
 *
 * Applying is a **local** operation — every repository underneath writes Room-first and enqueues an
 * outbox row in one transaction — so it completes at disk speed and the server outcome is the sync
 * engine's business. That is why there is no progress bar here and no "37 of 40 succeeded" dialog:
 * at the moment Apply returns, the server has not been consulted.
 *
 * Wiring only. The rendering is [BulkEditContent], split out the way `ForgotPasswordContent` is, so
 * every state this screen can reach is reachable in a test without a live ViewModel.
 *
 * @param bookIds the selected books.
 * @param onBack leave the screen.
 * @param onApplied leave after a successful apply, told how many books actually changed.
 * @param viewModel scoped to this selection; never switched.
 */
@Composable
fun BulkEditScreen(
    bookIds: List<String>,
    onBack: () -> Unit,
    onApplied: (changedCount: Int) -> Unit,
    viewModel: BulkEditViewModel = koinViewModel(parameters = { parametersOf(bookIds) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BulkEditEvent.Applied -> onApplied(event.changedCount)

                // Already surfaced globally through the error bus, which the ViewModel emits to on
                // the same failure; showing it here as well would report one failure twice. The
                // event still carries the committed count for the clients that read no bus.
                is BulkEditEvent.Failed -> Unit
            }
        }
    }

    BulkEditContent(
        state = state,
        onBack = onBack,
        onApply = viewModel::apply,
        onPublisherChange = viewModel::setPublisher,
        onYearChange = viewModel::setYear,
        onLanguageChange = viewModel::setLanguage,
    )
}

/**
 * The bulk editor as it appears — hero, form, preview, and the counts that have to be true.
 *
 * Two numbers on this screen are easy to get wrong and expensive to get wrong. The confirm button
 * counts the books that will **change**, not the books that were selected: promising forty and then
 * reporting twelve is the same overstatement the preview exists to prevent. And when fewer books
 * loaded than were chosen — a book deleted from another device between the grid and here — the
 * difference is stated rather than quietly acted on. An operation with no undo does not get to do
 * less than it was asked to without saying so.
 *
 * The confirm action is a docked button rather than a text action in a bar, because it is the
 * destructive end of the screen and belongs under the thumb that has just finished reading the
 * preview — not tucked into the corner the back button lives in. Above the medium window-size class
 * it becomes a corner action instead, and the two cards unfold side by side.
 *
 * @param state what to show.
 * @param onBack leave the screen.
 * @param onApply commit every instruction to every loaded book.
 * @param onPublisherChange the publisher field changed.
 * @param onYearChange the year field changed; null clears the instruction.
 * @param onLanguageChange the language field changed.
 * @param modifier Modifier for the screen.
 */
@Composable
internal fun BulkEditContent(
    state: BulkEditUiState,
    onBack: () -> Unit,
    onApply: () -> Unit,
    onPublisherChange: (String) -> Unit,
    onYearChange: (Int?) -> Unit,
    onLanguageChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editing = state as? BulkEditUiState.Editing
    val wide =
        currentWindowAdaptiveInfo()
            .windowSizeClass
            .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    ListenUpScaffold(
        modifier = modifier,
        // The hero bleeds behind the status bar and insets its own content, so the scaffold keeps
        // only the horizontal insets; its bottom is the mini-player spacer's, as always.
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal),
        bottomBar = {
            if (editing != null && !wide) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                    ConfirmButton(editing, onApply, Modifier.fillMaxWidth().padding(PageMargin))
                }
            }
        },
        floatingActionButton = {
            if (editing != null && wide) ConfirmButton(editing, onApply, fillWidth = false)
        },
    ) { padding ->
        BulkEditBody(
            state = state,
            padding = padding,
            wide = wide,
            onBack = onBack,
            onPublisherChange = onPublisherChange,
            onYearChange = onYearChange,
            onLanguageChange = onLanguageChange,
        )
    }
}

/**
 * The hero and everything below it, in one scroll.
 *
 * The hero scrolls with the content rather than pinning: it is a full colour block with a cover
 * cluster in it, and a block that size holding a third of a phone screen hostage while someone
 * types into the field beneath it would be the opposite of generous.
 */
@Composable
private fun BulkEditBody(
    state: BulkEditUiState,
    padding: PaddingValues,
    wide: Boolean,
    onBack: () -> Unit,
    onPublisherChange: (String) -> Unit,
    onYearChange: (Int?) -> Unit,
    onLanguageChange: (String) -> Unit,
) {
    val editing = state as? BulkEditUiState.Editing
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            // The scaffold already reserved the bottom; consuming it stops imePadding() adding a
            // second band between the focused field and the keyboard.
            .consumeWindowInsets(padding)
            .imePadding()
            .verticalScroll(rememberScrollState()),
    ) {
        BulkEditHero(
            // Silent until the selection is read: a title counting books nobody has loaded yet
            // would be the screen's first untrue statement.
            title = editing?.let { titleFor(it.bookCount) }.orEmpty(),
            selectedCount = editing?.requestedCount,
            books = editing?.selectionSample.orEmpty(),
            bookCount = editing?.bookCount ?: 0,
            onBack = onBack,
        )
        if (editing == null) {
            Box(Modifier.fillMaxWidth().padding(LoadingPadding), Alignment.Center) {
                ListenUpLoadingIndicator()
            }
        } else {
            BulkEditCards(
                state = editing,
                wide = wide,
                onPublisherChange = onPublisherChange,
                onYearChange = onYearChange,
                onLanguageChange = onLanguageChange,
            )
        }
    }
}

/**
 * The two cards, folded on a phone and unfolded on a wide window.
 *
 * Nothing is added and nothing is hidden between the two — the wide layout is the phone one opened
 * out, so a tablet user and a phone user are reading the same screen.
 */
@Composable
private fun BulkEditCards(
    state: BulkEditUiState.Editing,
    wide: Boolean,
    onPublisherChange: (String) -> Unit,
    onYearChange: (Int?) -> Unit,
    onLanguageChange: (String) -> Unit,
) {
    val publishing: @Composable () -> Unit = {
        StudioCard(title = stringResource(Res.string.bulk_edit_card_publishing)) {
            Column(verticalArrangement = Arrangement.spacedBy(CardGap)) {
                Text(
                    stringResource(Res.string.bulk_edit_card_publishing_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                BulkEditForm(
                    state = state,
                    onPublisherChange = onPublisherChange,
                    onYearChange = onYearChange,
                    onLanguageChange = onLanguageChange,
                    stacked = !wide,
                )
            }
        }
    }
    val preview: @Composable () -> Unit = {
        StudioCard(title = stringResource(Res.string.bulk_edit_card_preview)) {
            BulkEditPreview(rows = state.preview, bookCount = state.bookCount)
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(if (wide) WidePageMargin else PageMargin),
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        SomeNotLoadedNotice(bookCount = state.bookCount, requestedCount = state.requestedCount)
        if (wide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(WidePageMargin)) {
                Box(Modifier.weight(FORM_COLUMN_WEIGHT)) { publishing() }
                Box(Modifier.weight(PREVIEW_COLUMN_WEIGHT)) { preview() }
            }
        } else {
            publishing()
            preview()
        }
    }
}

/**
 * What the confirm action promises, and whether it is offered at all.
 *
 * The same component in both layouts — docked across the bottom on a phone, a corner action on a
 * wide window — so the label, the count and the disabled state cannot drift between them. Disabled
 * means disabled: a control that merely *looks* unavailable is still announced to a screen reader
 * and still tapped, and this one has no undo behind it.
 */
@Composable
private fun ConfirmButton(
    state: BulkEditUiState.Editing,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = true,
) {
    ListenUpButton(
        text =
            if (state.isApplying) {
                stringResource(Res.string.bulk_edit_applying)
            } else {
                applyLabelFor(state.changedBookCount)
            },
        onClick = onApply,
        modifier = modifier,
        enabled = state.canApply && !state.isApplying,
        fillMaxWidth = fillWidth,
        leadingIcon = Icons.Outlined.Check,
    )
}

/**
 * The books that were chosen but could not be read, when there are any.
 *
 * Silent in the normal case. When the selection has shrunk — a book deleted from another device
 * between the grid and this screen is the realistic way — the shortfall is stated before the form,
 * because the alternative is a bulk edit that quietly touches fewer books than were picked and
 * offers no way to notice. It wears the tertiary container rather than the error one: nothing has
 * gone wrong, the screen is simply smaller than the tap that opened it.
 */
@Composable
private fun SomeNotLoadedNotice(
    bookCount: Int,
    requestedCount: Int,
) {
    val missing = requestedCount - bookCount
    if (missing <= 0) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Outlined.CloudOff, contentDescription = null, modifier = Modifier.size(NoticeIconSize))
            Text(
                text =
                    if (missing == 1) {
                        stringResource(Res.string.bulk_edit_some_not_loaded_one, requestedCount)
                    } else {
                        stringResource(Res.string.bulk_edit_some_not_loaded_plural, missing, requestedCount)
                    },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** How the screen names the selection. One book gets its own sentence rather than a counted one. */
@Composable
private fun titleFor(bookCount: Int): String =
    if (bookCount == 1) {
        stringResource(Res.string.bulk_edit_title_one)
    } else {
        stringResource(Res.string.bulk_edit_title_plural, bookCount)
    }

/**
 * What the confirm action promises.
 *
 * Counted in books that will **change**, never in books that were selected. The button, the preview
 * and the "n books updated" that follows all come from the same number, so none of the three can
 * overstate the other two.
 *
 * Zero is named rather than counted. "Change 0 books" is true, but it is the resting state of an
 * untouched screen — the first thing every user reads here — and a count of nothing reads as a bug
 * rather than as an invitation. The button says what it does; the count arrives with something to
 * count, and the body copy carries the explanation.
 */
@Composable
private fun applyLabelFor(changedBookCount: Int): String =
    when (changedBookCount) {
        0 -> stringResource(Res.string.bulk_edit_apply_none)
        1 -> stringResource(Res.string.bulk_edit_apply_one)
        else -> stringResource(Res.string.bulk_edit_apply_plural, changedBookCount)
    }
