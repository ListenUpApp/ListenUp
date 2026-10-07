package com.calypsan.listenup.client.features.match

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.BottomAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.error.localized
import com.calypsan.listenup.client.presentation.match.ApplySummary
import com.calypsan.listenup.client.presentation.match.ReviewUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.match_applying
import listenup.composeapp.generated.resources.match_apply_changes
import listenup.composeapp.generated.resources.match_bar_chapter_name_one
import listenup.composeapp.generated.resources.match_bar_chapter_names
import listenup.composeapp.generated.resources.match_bar_cover
import listenup.composeapp.generated.resources.match_bar_field_one
import listenup.composeapp.generated.resources.match_bar_fields
import listenup.composeapp.generated.resources.match_bar_nothing
import listenup.composeapp.generated.resources.match_nothing_was_changed
import org.jetbrains.compose.resources.stringResource

/** Test tag of the Apply bar's summary line. */
internal const val APPLY_SUMMARY_TAG = "match-apply-summary"

/** Test tag of the Apply changes button. */
internal const val APPLY_BUTTON_TAG = "match-apply-button"

/** The sticky foot of Review: an Apply error (when there is one) above the docked Apply bar. */
@Composable
internal fun ApplyArea(
    ready: ReviewUiState.Ready,
    onApply: () -> Unit,
) {
    Column {
        ready.applyError?.let { ApplyErrorLine(it) }
        ApplyBar(summary = ready.applyBar, applying = ready.applying, onApply = onApply)
    }
}

/** "5 fields · cover · 16 chapter names", or "Nothing selected". */
@Composable
internal fun applySummaryText(summary: ApplySummary): String {
    val parts =
        listOfNotNull(
            summary.fieldCount.takeIf { it > 0 }?.let {
                if (it == 1) stringResource(Res.string.match_bar_field_one) else stringResource(Res.string.match_bar_fields, it)
            },
            stringResource(Res.string.match_bar_cover).takeIf { summary.coverChanges },
            summary.chapterNameCount.takeIf { it > 0 }?.let {
                if (it == 1) {
                    stringResource(Res.string.match_bar_chapter_name_one)
                } else {
                    stringResource(Res.string.match_bar_chapter_names, it)
                }
            },
        )
    return if (parts.isEmpty()) stringResource(Res.string.match_bar_nothing) else parts.joinToString(DOT)
}

/**
 * The docked toolbar: the summary and the extended Apply changes button. It grows with its text (a minimum
 * height, never a fixed one); at the largest text sizes the summary moves above a full-width button so the
 * button's label stays on one line.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ApplyBar(
    summary: ApplySummary,
    applying: Boolean,
    onApply: () -> Unit,
) {
    val stacked = LocalDensity.current.fontScale >= LARGE_TEXT_SCALE
    val summaryText = if (applying) stringResource(Res.string.match_applying) else applySummaryText(summary)
    val summaryLine: @Composable (Modifier) -> Unit = { modifier ->
        Text(
            text = summaryText,
            style = MaterialTheme.typography.titleSmall,
            modifier =
                modifier
                    .testTag(APPLY_SUMMARY_TAG)
                    .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    val button: @Composable (Modifier, Boolean) -> Unit = { modifier, fill ->
        ListenUpButton(
            text = stringResource(Res.string.match_apply_changes),
            onClick = onApply,
            enabled = summary.canApply,
            isLoading = applying,
            fillMaxWidth = fill,
            leadingIcon = Icons.Filled.Check,
            modifier = modifier.testTag(APPLY_BUTTON_TAG),
        )
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().heightIn(min = BottomAppBarDefaults.FlexibleBottomAppBarHeight),
    ) {
        if (stacked) {
            Column(
                modifier = Modifier.padding(horizontal = Spacing.screenMargin, vertical = Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                summaryLine(Modifier)
                button(Modifier, true)
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.screenMargin, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                summaryLine(Modifier.weight(1f))
                button(Modifier, false)
            }
        }
    }
}

/** "<what went wrong> Nothing was changed." — inline above the bar, read out at once. */
@Composable
private fun ApplyErrorLine(error: AppError) {
    val message = error.localized()
    val nothingChanged = stringResource(Res.string.match_nothing_was_changed)
    Text(
        text = if (message.contains(nothingChanged)) message else "$message $nothingChanged",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenMargin, vertical = Spacing.sm)
                .semantics { liveRegion = LiveRegionMode.Assertive },
    )
}
