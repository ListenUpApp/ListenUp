package com.calypsan.listenup.client.features.match

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SectionSegment
import com.calypsan.listenup.client.design.components.TonalLabel
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.theme.extendedColors
import com.calypsan.listenup.client.presentation.match.CandidateUi
import com.calypsan.listenup.client.presentation.match.FindUiState
import com.calypsan.listenup.client.presentation.match.PartialFailure
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.match_best_match
import listenup.composeapp.generated.resources.match_compare_a11y
import listenup.composeapp.generated.resources.match_maybe
import listenup.composeapp.generated.resources.match_partial_banner
import listenup.composeapp.generated.resources.match_result_count_one
import listenup.composeapp.generated.resources.match_results_count
import listenup.composeapp.generated.resources.match_retry_source
import listenup.composeapp.generated.resources.match_review_this_match_a11y
import listenup.composeapp.generated.resources.match_strong_match
import listenup.composeapp.generated.resources.match_your_current_link
import org.jetbrains.compose.resources.stringResource

/** Test tag of one candidate row, by the candidate's stable id. */
internal fun candidateTag(id: String): String = "match-candidate-$id"

/** The results: their count (announced), the partial banner, then Strong match and Maybe groups. */
internal fun LazyListScope.candidateGroups(
    results: FindUiState.Results,
    highlightPicked: Boolean,
    onPick: (CandidateUi) -> Unit,
    onCompare: (CandidateUi) -> Unit,
    onRetrySources: () -> Unit,
) {
    item(key = "count") { ResultCount(count = results.all.size) }
    results.partialFailure?.let { partial -> item(key = "partial") { PartialBanner(partial, onRetrySources) } }
    listOf(
        "strong" to results.strong,
        "maybe" to results.maybe,
    ).filter { it.second.isNotEmpty() }.forEach { (key, candidates) ->
        item(key = key) {
            SectionGroup(
                label =
                    stringResource(
                        if (candidates.first().tier == MatchTier.STRONG) Res.string.match_strong_match else Res.string.match_maybe,
                    ),
            ) {
                candidates.forEach { candidate ->
                    CandidateRow(
                        candidate = candidate,
                        picked = highlightPicked && candidate.key == results.pickedKey,
                        onPick = { onPick(candidate) },
                        onCompare = { onCompare(candidate) },
                    )
                }
            }
        }
    }
}

/** "4 results", read out politely when a search lands. */
@Composable
private fun ResultCount(count: Int) {
    Text(
        text =
            if (count == 1) {
                stringResource(Res.string.match_result_count_one)
            } else {
                stringResource(Res.string.match_results_count, count)
            },
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** "Hardcover didn't answer, so these results are from Audible and iTunes." with Retry Hardcover. */
@Composable
private fun PartialBanner(
    partial: PartialFailure,
    onRetry: () -> Unit,
) {
    val failed = sourcesPhrase(partial.failed)
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                text = stringResource(Res.string.match_partial_banner, failed, sourcesPhrase(partial.answered)),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(Res.string.match_retry_source, failed))
            }
        }
    }
}

/** One candidate: cover, badges, title, facts, up to three reasons, and where it was found. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CandidateRow(
    candidate: CandidateUi,
    picked: Boolean,
    onPick: () -> Unit,
    onCompare: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val sources = candidate.foundIn.map { it.source }
    val reviewLabel =
        stringResource(Res.string.match_review_this_match_a11y, candidate.title, sourcesPhrase(sources))
    SectionSegment(modifier = Modifier.testTag(candidateTag(candidate.id))) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (picked) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                    .semantics { selected = picked }
                    .clickable(onClickLabel = reviewLabel, role = Role.Button) {
                        haptics.press()
                        onPick()
                    }.padding(start = Spacing.md, top = Spacing.md, bottom = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            RemoteCover(url = candidate.coverUrl, contentDescription = null, modifier = Modifier.size(56.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (candidate.isBest || candidate.isCurrentLink) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        if (candidate.isBest) {
                            TonalLabel(
                                label = stringResource(Res.string.match_best_match),
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                icon = Icons.Outlined.StarOutline,
                            )
                        }
                        if (candidate.isCurrentLink) {
                            TonalLabel(label = stringResource(Res.string.match_your_current_link), icon = Icons.Outlined.Link)
                        }
                    }
                }
                Text(candidate.title, style = MaterialTheme.typography.titleMedium)
                val facts = candidate.factsLine()
                if (facts.isNotEmpty()) {
                    Text(facts, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ReasonsLine(candidate)
                Text(
                    text = sources.map { it.label }.distinct().joinToString(DOT),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onCompare) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = stringResource(Res.string.match_compare_a11y, candidate.title),
                )
            }
        }
    }
}

/** Up to three reasons; a Strong match leads with its check, so the tier never rests on colour alone. */
@Composable
private fun ReasonsLine(candidate: CandidateUi) {
    if (candidate.reasons.isEmpty()) return
    val strong = candidate.tier == MatchTier.STRONG
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (strong) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.extendedColors.success,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = candidate.reasons.map { it.displayText() }.joinToString(DOT),
            style = MaterialTheme.typography.bodySmall,
            color = if (strong) MaterialTheme.extendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
