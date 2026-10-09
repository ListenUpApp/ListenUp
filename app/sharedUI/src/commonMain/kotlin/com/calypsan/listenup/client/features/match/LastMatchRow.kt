package com.calypsan.listenup.client.features.match

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.util.isJustNow
import com.calypsan.listenup.client.design.util.relativeTime
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailViewModel
import com.calypsan.listenup.client.presentation.bookdetail.LastMatchUi
import com.calypsan.listenup.client.presentation.error.localized
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.match_details_matched
import listenup.composeapp.generated.resources.match_details_matched_by
import listenup.composeapp.generated.resources.match_details_matched_by_just_now
import listenup.composeapp.generated.resources.match_details_matched_just_now
import listenup.composeapp.generated.resources.match_see_what_changed
import listenup.composeapp.generated.resources.match_undo_last_match
import listenup.composeapp.generated.resources.match_undoing
import org.jetbrains.compose.resources.stringResource

/** Test tag of Book Detail's last-match row. */
internal const val LAST_MATCH_ROW_TAG = "last-match-row"

/** Book Detail's last-match row for [viewModel]'s book; renders nothing while there is no match to undo. */
@Composable
fun LastMatchHost(
    viewModel: BookDetailViewModel,
    modifier: Modifier = Modifier,
) {
    val lastMatch by viewModel.lastMatch.collectAsStateWithLifecycle()
    lastMatch?.let { row ->
        LastMatchRow(
            lastMatch = row,
            onSeeWhatChanged = viewModel::seeWhatChanged,
            onCloseWhatChanged = viewModel::closeWhatChanged,
            onUndo = viewModel::undoLastMatch,
            modifier = modifier,
        )
    }
}

/**
 * "Details matched 2d ago · See what changed · Undo last match" (canvas A-04): a tonal row under Book Detail's
 * actions while the book's last match can still be undone. See what changed opens the receipt's own list of
 * changes; Undo goes through the same use case as the receipt's. A failed Undo says why under the sentence and
 * stays, to be tried again. The actions wrap beneath the sentence when the text is large.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LastMatchRow(
    lastMatch: LastMatchUi,
    onSeeWhatChanged: () -> Unit,
    onCloseWhatChanged: () -> Unit,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag(LAST_MATCH_ROW_TAG),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(start = Spacing.md, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.padding(top = Spacing.sm, end = Spacing.sm),
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(lastMatchSentence(lastMatch), style = MaterialTheme.typography.bodyMedium)
                    lastMatch.undoError?.let { error ->
                        Text(
                            text = error.localized(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onSeeWhatChanged, enabled = !lastMatch.undoing) {
                    Text(stringResource(Res.string.match_see_what_changed))
                }
                TextButton(onClick = onUndo, enabled = !lastMatch.undoing) {
                    Text(
                        stringResource(
                            if (lastMatch.undoing) Res.string.match_undoing else Res.string.match_undo_last_match,
                        ),
                    )
                }
            }
        }
    }

    if (lastMatch.showingChanges) {
        WhatChangedSheet(receipt = lastMatch.receipt, onDismiss = onCloseWhatChanged)
    }
}

/** "Details matched just now", "Details matched 2d ago", or with "… by Sam" when someone else matched it. */
@Composable
internal fun lastMatchSentence(lastMatch: LastMatchUi): String {
    val justNow = isJustNow(lastMatch.appliedAtMs)
    val by = lastMatch.matchedBy
    return when {
        by == null && justNow -> stringResource(Res.string.match_details_matched_just_now)
        by == null -> stringResource(Res.string.match_details_matched, relativeTime(lastMatch.appliedAtMs))
        justNow -> stringResource(Res.string.match_details_matched_by_just_now, by)
        else -> stringResource(Res.string.match_details_matched_by, relativeTime(lastMatch.appliedAtMs), by)
    }
}
