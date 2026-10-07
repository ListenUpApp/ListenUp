package com.calypsan.listenup.client.features.match

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.match.EditionFormat
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.design.components.ListenUpButton
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.presentation.match.CandidateUi
import com.calypsan.listenup.client.presentation.match.RegionUi
import com.calypsan.listenup.client.presentation.match.YourCopyUi
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.match_back_to_results
import listenup.composeapp.generated.resources.match_compare_title
import listenup.composeapp.generated.resources.match_empty_value
import listenup.composeapp.generated.resources.match_maybe
import listenup.composeapp.generated.resources.match_not_listed
import listenup.composeapp.generated.resources.match_review_this_match
import listenup.composeapp.generated.resources.match_row_chapters
import listenup.composeapp.generated.resources.match_row_format
import listenup.composeapp.generated.resources.match_row_found_in
import listenup.composeapp.generated.resources.match_row_length
import listenup.composeapp.generated.resources.match_row_narrator
import listenup.composeapp.generated.resources.match_row_store
import listenup.composeapp.generated.resources.match_row_year
import listenup.composeapp.generated.resources.match_same
import listenup.composeapp.generated.resources.match_strong_match
import listenup.composeapp.generated.resources.match_this_match
import listenup.composeapp.generated.resources.match_within_minutes
import listenup.composeapp.generated.resources.match_worldwide
import listenup.composeapp.generated.resources.match_your_copy
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

private const val SAME_LENGTH_MS = 30_000L
private const val MS_PER_MINUTE = 60_000L
private const val CLOSE_LENGTH_MINUTES = 5

/**
 * Compare with your copy: Length, Narrator, Chapters, Year, Format, Store and Found in, side by side, from Find's
 * data alone. A value a catalogue doesn't give reads "Not listed". It leads to Review this match.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompareSheet(
    candidate: CandidateUi,
    yourCopy: YourCopyUi?,
    region: RegionUi?,
    onReview: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.screenMargin)
                    .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Text(
                text = stringResource(Res.string.match_compare_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            Column {
                Text(candidate.title, style = MaterialTheme.typography.titleMedium)
                val tier =
                    stringResource(
                        if (candidate.tier == MatchTier.STRONG) Res.string.match_strong_match else Res.string.match_maybe,
                    )
                Text(
                    text = (listOf(tier) + candidate.foundIn.map { it.source.label }.distinct()).joinToString(DOT),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CompareTable(candidate, yourCopy, region)
            ListenUpButton(text = stringResource(Res.string.match_review_this_match), onClick = onReview)
            ListenUpButton(text = stringResource(Res.string.match_back_to_results), onClick = onDismiss, filled = false)
        }
    }
}

@Composable
private fun CompareTable(
    candidate: CandidateUi,
    yourCopy: YourCopyUi?,
    region: RegionUi?,
) {
    val notListed = stringResource(Res.string.match_not_listed)
    Column {
        CompareRow(
            label = "",
            yours = stringResource(Res.string.match_your_copy),
            theirs = stringResource(Res.string.match_this_match),
            header = true,
        )
        CompareRow(
            label = stringResource(Res.string.match_row_length),
            yours = yourCopy?.durationMs?.let(::lengthText) ?: notListed,
            theirs = candidate.durationMs?.let(::lengthText) ?: notListed,
            note = lengthNote(yourCopy?.durationMs, candidate.durationMs),
        )
        val sameNarrator =
            yourCopy != null && yourCopy.narrators.isNotEmpty() &&
                yourCopy.narrators.map { it.lowercase() } == candidate.narrators.map { it.lowercase() }
        CompareRow(
            label = stringResource(Res.string.match_row_narrator),
            yours = yourCopy?.narrators?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: notListed,
            theirs = candidate.narrators.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: notListed,
            note = if (sameNarrator) stringResource(Res.string.match_same) else null,
        )
        CompareRow(
            label = stringResource(Res.string.match_row_chapters),
            yours = yourCopy?.chapterCount?.toString() ?: notListed,
            theirs = candidate.chapterCount?.toString() ?: notListed,
        )
        CompareRow(
            label = stringResource(Res.string.match_row_year),
            yours = yourCopy?.year?.toString() ?: notListed,
            theirs = candidate.year?.toString() ?: notListed,
        )
        CompareRow(
            label = stringResource(Res.string.match_row_format),
            yours = yourCopy?.let { (if (it.isAbridged) EditionFormat.ABRIDGED else EditionFormat.UNABRIDGED).displayName() } ?: notListed,
            theirs = candidate.format?.displayName() ?: notListed,
        )
        val worldwide = stringResource(Res.string.match_worldwide)
        CompareRow(
            label = stringResource(Res.string.match_row_store),
            yours = region?.region?.displayName ?: notListed,
            theirs =
                candidate.foundIn
                    .firstNotNullOfOrNull { it.region }
                    ?.let { MetadataLocale(region = it).displayName } ?: worldwide,
        )
        CompareRow(
            label = stringResource(Res.string.match_row_found_in),
            yours = stringResource(Res.string.match_empty_value),
            theirs = candidate.foundIn.map { it.source.label }.distinct().joinToString(", "),
        )
    }
}

/** "Same" within 30 seconds, "Within 1 min" up to five, otherwise nothing: the numbers speak. */
@Composable
private fun lengthNote(
    yours: Long?,
    theirs: Long?,
): String? {
    if (yours == null || theirs == null) return null
    val delta = abs(yours - theirs)
    return when {
        delta <= SAME_LENGTH_MS -> {
            stringResource(Res.string.match_same)
        }

        delta <= CLOSE_LENGTH_MINUTES * MS_PER_MINUTE -> {
            val minutes = ((delta + MS_PER_MINUTE - 1) / MS_PER_MINUTE).toInt()
            stringResource(Res.string.match_within_minutes, minutes)
        }

        else -> {
            null
        }
    }
}

@Composable
private fun CompareRow(
    label: String,
    yours: String,
    theirs: String,
    note: String? = null,
    header: Boolean = false,
) {
    val style = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = Spacing.sm).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text(yours, style = style, modifier = Modifier.weight(1.2f))
        Column(modifier = Modifier.weight(1.2f)) {
            Text(theirs, style = style, fontWeight = if (header) null else FontWeight.Medium)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        }
    }
    if (!header) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
