package com.calypsan.listenup.web.features.match

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.dto.match.MatchReason
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.client.presentation.match.CandidateUi
import com.calypsan.listenup.client.presentation.match.FindUiState
import com.calypsan.listenup.client.presentation.match.YourCopyUi
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.EmptyLook
import com.calypsan.listenup.web.design.EmptyState
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.design.coverUrl
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Table
import org.jetbrains.compose.web.dom.Tbody
import org.jetbrains.compose.web.dom.Td
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.dom.Th
import org.jetbrains.compose.web.dom.Thead
import org.jetbrains.compose.web.dom.Tr

/**
 * Compare editions (W-02): every match side by side with your copy — length, narrator, chapters,
 * year, format, store, found in. It uses only what Find already has, so it costs no call, and the
 * results it came from are exactly where they were when [onBack] returns.
 *
 * A real table: a screen reader walks it by row and column, and hears each value with its header.
 * Each column ends in "Review this match", named for the match it opens.
 */
@Composable
internal fun ComparePage(
    find: FindUiState,
    bookId: String,
    onChooseStore: (MetadataLocale) -> Unit,
    onBack: () -> Unit,
    onReview: (CandidateUi) -> Unit,
) {
    val results =
        when (find) {
            is FindUiState.Results -> find
            is FindUiState.Searching -> find.previous
            is FindUiState.Failed -> null
        }
    PageHeader(
        title = COMPARE,
        subtitle = "Each match side by side with your copy. Your results stay where they were.",
        actions = { Button(kind = ButtonKind.Secondary, onClick = onBack) { Text("Back to results") } },
    )
    results?.region?.let { StoreMenu(it, onChooseStore) }
    val candidates = results?.all.orEmpty()
    if (candidates.isEmpty()) {
        EmptyState(title = "There are no matches to compare yet.", look = EmptyLook.Inline)
        return
    }
    Div(attrs = { classes("bmx-cmp-wrap") }) {
        Table(attrs = { classes("bmx-cmp") }) {
            Thead {
                Tr {
                    Th(attrs = { attr("scope", "col") }) { Span(attrs = { classes("sr-only") }) { Text("Detail") } }
                    Th(attrs = { attr("scope", "col") }) {
                        Div(attrs = { classes("bmx-cmp-col") }) {
                            Art(url = find.yourCopy?.coverHash?.let { coverUrl(bookId, it, width = ART_WIDTH) })
                            Text("Your copy")
                        }
                    }
                    candidates.forEach { candidate ->
                        Th(attrs = { attr("scope", "col") }) {
                            Div(attrs = { classes("bmx-cmp-col") }) {
                                Art(url = candidate.coverUrl)
                                Badges(candidate)
                                Text(candidate.title)
                            }
                        }
                    }
                }
            }
            Tbody {
                compareRows(find.yourCopy, candidates).forEach { row ->
                    Tr {
                        Th(attrs = { attr("scope", "row") }) { Text(row.label) }
                        Td { Text(row.yours) }
                        row.theirs.forEach { cell ->
                            Td {
                                Text(cell.value)
                                cell.note?.let { Span(attrs = { classes("bmx-cmp-note") }) { Text(it) } }
                            }
                        }
                    }
                }
                Tr {
                    Td {}
                    Td {}
                    candidates.forEach { candidate ->
                        Td {
                            Button(
                                kind = ButtonKind.Secondary,
                                onClick = { onReview(candidate) },
                                label = "Review this match, ${candidate.title}, ${sourcesText(candidate.foundIn.map { it.source })}",
                            ) { Text("Review this match") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Badges(candidate: CandidateUi) {
    Span(attrs = { classes("bmx-badges") }) {
        if (candidate.isBest) Span(attrs = { classes("bmx-badge", "is-best") }) { Text("Best match") }
        if (candidate.isCurrentLink) Span(attrs = { classes("bmx-badge") }) { Text("Your current link") }
        Span(attrs = { classes("bmx-badge") }) { Text(if (candidate.tier == MatchTier.STRONG) "Strong match" else "Maybe") }
    }
}

/** One cell of a match's column: its value, and what its reasons say about it. */
internal class CompareCell(
    val value: String,
    val note: String? = null,
)

/** One row of the comparison: your value, then each match's. */
internal class CompareRow(
    val label: String,
    val yours: String,
    val theirs: List<CompareCell>,
)

/** The seven rows, in the canvas's order. A value nobody listed reads "Not listed". */
internal fun compareRows(
    copy: YourCopyUi?,
    candidates: List<CandidateUi>,
): List<CompareRow> =
    listOf(
        CompareRow(
            "Length",
            copy?.durationMs?.let(::lengthText) ?: NOT_LISTED,
            candidates.map { CompareCell(it.durationMs?.let(::lengthText) ?: NOT_LISTED, lengthNote(it.reasons)) },
        ),
        CompareRow(
            "Narrator",
            narratorsText(copy?.narrators.orEmpty()) ?: NOT_LISTED,
            candidates.map { CompareCell(narratorsText(it.narrators) ?: NOT_LISTED, narratorNote(it.reasons)) },
        ),
        CompareRow(
            "Chapters",
            copy?.chapterCount?.toString() ?: NOT_LISTED,
            candidates.map { CompareCell(it.chapterCount?.toString() ?: NOT_LISTED) },
        ),
        CompareRow(
            "Year",
            copy?.year?.toString() ?: NOT_LISTED,
            candidates.map { CompareCell(it.year?.toString() ?: NOT_LISTED) },
        ),
        CompareRow(
            "Format",
            copy?.let { if (it.isAbridged) "Abridged" else "Unabridged" } ?: NOT_LISTED,
            candidates.map { candidate ->
                CompareCell(
                    candidate.format?.let(::formatText) ?: NOT_LISTED,
                    "Different edition".takeIf { candidate.reasons.any { it is MatchReason.DifferentEdition } },
                )
            },
        ),
        CompareRow(
            "Store",
            "—",
            candidates.map { candidate ->
                val stores = candidate.foundIn.filter { it.region != null }.map { "${it.source.label} ${it.region.orEmpty().uppercase()}" }
                CompareCell(stores.joinToString(", ").ifBlank { NOT_LISTED })
            },
        ),
        CompareRow(
            "Found in",
            "In your library",
            candidates.map { CompareCell(sourcesText(it.foundIn.map { found -> found.source }).ifBlank { NOT_LISTED }) },
        ),
    )

private fun lengthNote(reasons: List<MatchReason>): String? =
    reasons.firstNotNullOfOrNull { reason ->
        when (reason) {
            MatchReason.SameLength -> "Same length"
            is MatchReason.LengthWithin -> "Within ${reason.minutes} min"
            is MatchReason.LengthDiffers -> lengthDeltaText(reason.deltaMinutes)
            else -> null
        }
    }

private fun narratorNote(reasons: List<MatchReason>): String? =
    reasons.firstNotNullOfOrNull { reason ->
        when (reason) {
            MatchReason.SameNarrator -> "Same narrator"
            MatchReason.DifferentNarrators -> "Different narrators"
            else -> null
        }
    }

private const val NOT_LISTED = "Not listed"
private const val ART_WIDTH = 112
