package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.client.core.DurationFormatter
import kotlin.time.Duration.Companion.milliseconds

/**
 * Computed surface shared by [BookListItem] and [BookDetail].
 *
 * The two book projection types are deliberately separate — list surfaces never carry
 * [BookDetail.genres], [BookDetail.tags], or [BookDetail.allContributors], so a merged type
 * would always-empty those fields on list paths and risk spurious Flow re-emissions when
 * unrelated detail-only edits occur. This interface exists solely so the bodies of their
 * identical computed properties and [formatDuration] are defined once rather than duplicated.
 *
 * Implementers must supply the five raw fields this surface derives from; the computed
 * properties are default implementations on the interface.
 */
interface BookSummaryFields {
    val series: List<BookSeries>
    val coverPath: String?
    val coverHash: String? get() = null
    val authors: List<BookContributor>
    val narrators: List<BookContributor>
    val duration: Long

    val seriesId: String? get() = series.firstOrNull()?.seriesId
    val seriesName: String? get() = series.firstOrNull()?.seriesName
    val seriesSequence: String? get() = series.firstOrNull()?.sequence

    val hasCover: Boolean get() = coverPath != null

    val fullSeriesTitle: String?
        get() {
            val firstSeries = series.firstOrNull() ?: return null
            val name = firstSeries.seriesName
            val seq = firstSeries.sequence
            return if (seq != null && seq.isNotBlank()) "$name #$seq" else name
        }

    val authorNames: String get() = authors.joinToString(", ") { it.name }
    val narratorNames: String get() = narrators.joinToString(", ") { it.name }

    fun formatDuration(): String = DurationFormatter.hoursMinutes(duration.milliseconds)
}
