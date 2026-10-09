package com.calypsan.listenup.client.features.match

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.api.dto.match.AppliedChange
import com.calypsan.listenup.client.design.motion.LocalTouchExplorationActive
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.presentation.match.MatchReceiptUi
import com.calypsan.listenup.client.presentation.match.MatchReceiptUiState
import com.calypsan.listenup.client.presentation.match.MatchReceiptViewModel
import kotlinx.coroutines.delay
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.match_change_biography
import listenup.composeapp.generated.resources.match_change_chapter_names
import listenup.composeapp.generated.resources.match_change_cover
import listenup.composeapp.generated.resources.match_change_field
import listenup.composeapp.generated.resources.match_change_genres_added
import listenup.composeapp.generated.resources.match_change_genres_removed
import listenup.composeapp.generated.resources.match_change_moods_added
import listenup.composeapp.generated.resources.match_change_moods_removed
import listenup.composeapp.generated.resources.match_change_photo
import listenup.composeapp.generated.resources.match_dismiss
import listenup.composeapp.generated.resources.match_receipt_chapter_name_one
import listenup.composeapp.generated.resources.match_receipt_chapter_names
import listenup.composeapp.generated.resources.match_receipt_changed
import listenup.composeapp.generated.resources.match_receipt_cover_from
import listenup.composeapp.generated.resources.match_receipt_field_one
import listenup.composeapp.generated.resources.match_receipt_fields
import listenup.composeapp.generated.resources.match_receipt_nothing
import listenup.composeapp.generated.resources.match_see_what_changed
import listenup.composeapp.generated.resources.match_undo
import listenup.composeapp.generated.resources.match_undo_expired
import listenup.composeapp.generated.resources.match_undo_expired_person
import listenup.composeapp.generated.resources.match_undoing
import listenup.composeapp.generated.resources.match_undone
import listenup.composeapp.generated.resources.match_what_changed_title
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Test tag of the receipt snackbar. */
internal const val MATCH_RECEIPT_TAG = "match-receipt"

/** How long the receipt stays without a screen reader: a long snackbar's time, then it dismisses itself. */
private const val RECEIPT_VISIBLE_MS = 10_000L

/** Whose receipt a [MatchReceiptBanner] shows: a book's, or a person's, named in the sentence. */
sealed interface MatchReceiptSubject {
    /** A book: "Changed 5 fields, cover from …", with See what changed. */
    data object Book : MatchReceiptSubject

    /** A person: "Changed photo and biography for [name]". */
    data class Person(
        val name: String,
    ) : MatchReceiptSubject
}

/**
 * The receipt after Match details applied, for [subjectId] — a book on Book Detail, a person on the contributor
 * page: hosts that subject's [MatchReceiptViewModel]. Renders nothing until a match lands.
 */
@Composable
fun MatchReceiptHost(
    subjectId: String,
    modifier: Modifier = Modifier,
    subject: MatchReceiptSubject = MatchReceiptSubject.Book,
    viewModel: MatchReceiptViewModel =
        koinViewModel(key = "match-receipt-$subjectId", parameters = { parametersOf(subjectId) }),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MatchReceiptBanner(
        state = state,
        subject = subject,
        onUndo = viewModel::undo,
        onDismiss = viewModel::dismiss,
        modifier = modifier,
    )
}

/**
 * The receipt as a snackbar: "Changed 5 fields, cover from <source>, 16 chapter names" with See what changed
 * and Undo for a book, "Changed photo and biography for Ray Porter" with Undo for a person; then "Match
 * undone…" or "…can't be undone." It is announced, takes accessibility focus, and while a screen reader runs it
 * stays until dismissed; otherwise it dismisses itself after a long snackbar's time.
 */
@Composable
fun MatchReceiptBanner(
    state: MatchReceiptUiState,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subject: MatchReceiptSubject = MatchReceiptSubject.Book,
) {
    if (state is MatchReceiptUiState.None) return
    var seeingChanges by remember { mutableStateOf(false) }
    val screenReader = LocalTouchExplorationActive.current
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val focusRequester = remember { FocusRequester() }
    val busy = state is MatchReceiptUiState.Shown && state.undoing

    LaunchedEffect(state::class) { runCatching { focusRequester.requestFocus() } }
    LaunchedEffect(state::class, screenReader, seeingChanges, busy) {
        if (!screenReader && !seeingChanges && !busy) {
            delay(RECEIPT_VISIBLE_MS)
            currentOnDismiss()
        }
    }

    val message = receiptMessage(state, subject)

    Snackbar(
        modifier =
            modifier
                .padding(Spacing.md)
                .testTag(MATCH_RECEIPT_TAG)
                .focusRequester(focusRequester)
                .focusable()
                .semantics { liveRegion = LiveRegionMode.Polite },
        action =
            (state as? MatchReceiptUiState.Shown)?.let { shown ->
                {
                    Row {
                        // A person's sentence already names both changes, so their receipt has no list to open.
                        if (subject == MatchReceiptSubject.Book) {
                            TextButton(
                                onClick = { seeingChanges = true },
                                colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
                            ) { Text(stringResource(Res.string.match_see_what_changed)) }
                        }
                        if (shown.receipt.undoable) {
                            TextButton(
                                onClick = onUndo,
                                enabled = !shown.undoing,
                                colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
                            ) {
                                Text(
                                    stringResource(
                                        if (shown.undoing) Res.string.match_undoing else Res.string.match_undo,
                                    ),
                                )
                            }
                        }
                    }
                }
            },
        dismissAction = {
            IconButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.match_dismiss))
            }
        },
        actionOnNewLine = state is MatchReceiptUiState.Shown && subject == MatchReceiptSubject.Book,
    ) {
        Text(message)
    }

    if (seeingChanges && state is MatchReceiptUiState.Shown) {
        WhatChangedSheet(receipt = state.receipt, onDismiss = { seeingChanges = false })
    }
}

/** What the receipt says: the change, the Undo error, "Match undone…", or why it can't be undone. */
@Composable
private fun receiptMessage(
    state: MatchReceiptUiState,
    subject: MatchReceiptSubject,
): String =
    when (state) {
        is MatchReceiptUiState.Shown -> {
            state.undoError?.localized() ?: when (subject) {
                MatchReceiptSubject.Book -> receiptText(state.receipt)
                is MatchReceiptSubject.Person -> personReceiptText(state.receipt, subject.name)
            }
        }

        MatchReceiptUiState.Undone -> {
            stringResource(Res.string.match_undone)
        }

        MatchReceiptUiState.Expired -> {
            when (subject) {
                MatchReceiptSubject.Book -> stringResource(Res.string.match_undo_expired)
                is MatchReceiptSubject.Person -> stringResource(Res.string.match_undo_expired_person, subject.name)
            }
        }

        MatchReceiptUiState.None -> {
            ""
        }
    }

/** "Changed 5 fields, cover from <source>, 16 chapter names", or "Matched. Nothing needed changing." */
@Composable
internal fun receiptText(receipt: MatchReceiptUi): String {
    val parts =
        listOfNotNull(
            receipt.fieldCount.takeIf { it > 0 }?.let { fieldCount ->
                if (fieldCount ==
                    1
                ) {
                    stringResource(Res.string.match_receipt_field_one)
                } else {
                    stringResource(Res.string.match_receipt_fields, fieldCount)
                }
            },
            receipt.coverSource?.let { stringResource(Res.string.match_receipt_cover_from, it.label) },
            receipt.chapterNameCount.takeIf { it > 0 }?.let { chapterNameCount ->
                if (chapterNameCount == 1) {
                    stringResource(Res.string.match_receipt_chapter_name_one)
                } else {
                    stringResource(Res.string.match_receipt_chapter_names, chapterNameCount)
                }
            },
        )
    return if (parts.isEmpty()) {
        stringResource(Res.string.match_receipt_nothing)
    } else {
        stringResource(Res.string.match_receipt_changed, parts.joinToString(", "))
    }
}

/** See what changed: every change the match made, each with where it came from. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WhatChangedSheet(
    receipt: MatchReceiptUi,
    onDismiss: () -> Unit,
) {
    val title = stringResource(Res.string.match_what_changed_title)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState =
            rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        modifier = Modifier.semantics { paneTitle = title },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenMargin)
                    .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            receipt.changes.forEach { change ->
                change.lines().forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = Spacing.sm),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/** One applied change as the lines See what changed lists: "Description · from <source>". */
@Composable
private fun AppliedChange.lines(): List<String> =
    when (this) {
        is AppliedChange.Field -> {
            listOf(stringResource(Res.string.match_change_field, field.displayName(), source.label))
        }

        is AppliedChange.Cover -> {
            listOf(stringResource(Res.string.match_change_cover, source.label))
        }

        is AppliedChange.Genres -> {
            listOfNotNull(
                added.takeIf { it.isNotEmpty() }?.let { addedNames ->
                    stringResource(
                        Res.string.match_change_genres_added,
                        addedNames.joinToString(", "),
                    )
                },
                removed
                    .takeIf {
                        it.isNotEmpty()
                    }?.let { stringResource(Res.string.match_change_genres_removed, it.joinToString(", ")) },
            )
        }

        is AppliedChange.Moods -> {
            listOfNotNull(
                added.takeIf { it.isNotEmpty() }?.let { addedNames ->
                    stringResource(
                        Res.string.match_change_moods_added,
                        addedNames.joinToString(", "),
                    )
                },
                removed
                    .takeIf {
                        it.isNotEmpty()
                    }?.let { stringResource(Res.string.match_change_moods_removed, it.joinToString(", ")) },
            )
        }

        is AppliedChange.ChapterNames -> {
            listOf(stringResource(Res.string.match_change_chapter_names, count, source.label))
        }

        is AppliedChange.Photo -> {
            listOf(stringResource(Res.string.match_change_photo, source.label))
        }

        is AppliedChange.Biography -> {
            listOf(stringResource(Res.string.match_change_biography, source.label))
        }
    }
