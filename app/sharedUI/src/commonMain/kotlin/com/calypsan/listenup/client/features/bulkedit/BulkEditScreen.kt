package com.calypsan.listenup.client.features.bulkedit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditEvent
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditUiState
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.bulk_edit_apply_none
import listenup.composeapp.generated.resources.bulk_edit_apply_one
import listenup.composeapp.generated.resources.bulk_edit_apply_plural
import listenup.composeapp.generated.resources.bulk_edit_applying
import listenup.composeapp.generated.resources.bulk_edit_nothing_to_do
import listenup.composeapp.generated.resources.bulk_edit_some_not_loaded_one
import listenup.composeapp.generated.resources.bulk_edit_some_not_loaded_plural
import listenup.composeapp.generated.resources.bulk_edit_title_one
import listenup.composeapp.generated.resources.bulk_edit_title_plural
import listenup.composeapp.generated.resources.common_back
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

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
 * The bulk editor as it appears — form, preview, and the counts that have to be true.
 *
 * Two numbers on this screen are easy to get wrong and expensive to get wrong. The Apply button
 * counts the books that will **change**, not the books that were selected: promising forty and then
 * reporting twelve is the same overstatement the preview exists to prevent. And when fewer books
 * loaded than were chosen — a book deleted from another device between the grid and here — the
 * difference is stated rather than quietly acted on. An operation with no undo does not get to do
 * less than it was asked to without saying so.
 *
 * @param state what to show.
 * @param onBack leave the screen.
 * @param onApply commit every instruction to every loaded book.
 * @param onPublisherChange the publisher field changed.
 * @param onYearChange the year field changed; null clears the instruction.
 * @param onLanguageChange the language field changed.
 * @param modifier Modifier for the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
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

    ListenUpScaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                // Silent until the selection is read: a title counting books nobody has loaded yet
                // would be the screen's first untrue statement.
                title = { editing?.let { Text(titleFor(it.bookCount)) } },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.common_back))
                    }
                },
                actions = {
                    editing?.let {
                        TextButton(onClick = onApply, enabled = it.canApply && !it.isApplying) {
                            Text(
                                if (it.isApplying) {
                                    stringResource(Res.string.bulk_edit_applying)
                                } else {
                                    applyLabelFor(it.changedBookCount)
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        when (val current = state) {
            BulkEditUiState.Loading -> {
                Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) { ListenUpLoadingIndicator() }
            }

            is BulkEditUiState.Editing -> {
                BulkEditBody(current, padding, onPublisherChange, onYearChange, onLanguageChange)
            }
        }
    }
}

/**
 * The form and everything that reports on it.
 *
 * A column capped at a readable width and centred, rather than three text fields stretched across a
 * desktop window — the fields are the content, and content that wide is harder to read, not more
 * generous.
 */
@Composable
private fun BulkEditBody(
    state: BulkEditUiState.Editing,
    padding: PaddingValues,
    onPublisherChange: (String) -> Unit,
    onYearChange: (Int?) -> Unit,
    onLanguageChange: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(padding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SomeNotLoadedNotice(bookCount = state.bookCount, requestedCount = state.requestedCount)
            BulkEditForm(
                state = state,
                onPublisherChange = onPublisherChange,
                onYearChange = onYearChange,
                onLanguageChange = onLanguageChange,
            )
            if (state.preview.isEmpty()) {
                // An empty panel would read as a broken preview rather than an untouched form.
                Text(
                    stringResource(Res.string.bulk_edit_nothing_to_do),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                BulkEditPreview(rows = state.preview, bookCount = state.bookCount)
            }
        }
    }
}

/**
 * The books that were chosen but could not be read, when there are any.
 *
 * Silent in the normal case. When the selection has shrunk — a book deleted from another device
 * between the grid and this screen is the realistic way — the shortfall is stated before the form,
 * because the alternative is a bulk edit that quietly touches fewer books than were picked and
 * offers no way to notice.
 */
@Composable
private fun SomeNotLoadedNotice(
    bookCount: Int,
    requestedCount: Int,
) {
    val missing = requestedCount - bookCount
    if (missing <= 0) return

    Text(
        text =
            if (missing == 1) {
                stringResource(Res.string.bulk_edit_some_not_loaded_one, requestedCount)
            } else {
                stringResource(Res.string.bulk_edit_some_not_loaded_plural, missing, requestedCount)
            },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(16.dp),
    )
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
 * What Apply promises.
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

/** Text fields stop being easier to read past this; the rest of the window stays margin. */
private val ContentMaxWidth = 640.dp
