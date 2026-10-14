package com.calypsan.listenup.client.domain.model

/**
 * The book after a given one in its first series, as Home's cards and Now Playing's finished state draw it.
 * Produced only by `ObserveNextInSeriesUseCase`, so the two can never disagree (Home P-R4).
 *
 * @property narratorNames narrators joined with ", ", or null when the book names none.
 * @property sequenceLabel this book's place in the series as a person writes it ("2", "2.5"), or null.
 */
data class NextInSeries(
    val bookId: String,
    val title: String,
    val coverPath: String?,
    val coverHash: String?,
    val narratorNames: String?,
    val seriesName: String,
    val sequenceLabel: String?,
    val durationMs: Long,
)
