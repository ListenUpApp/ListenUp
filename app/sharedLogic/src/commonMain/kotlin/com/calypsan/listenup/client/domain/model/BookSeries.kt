package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.client.core.formatSeriesSequence

/**
 * Series membership with position within that series.
 */
data class BookSeries(
    val seriesId: String,
    val seriesName: String,
    val sequence: Double? = null, // e.g. 1.0, 1.5; null when unnumbered
) {
    /**
     * [sequence] as a person would write it: `1.0` reads `"1"`, `1.5` stays `"1.5"`, unnumbered is
     * null.
     *
     * Display sites read THIS, never [sequence]. The position is a number so that a 1.5 interquel
     * can exist and so books sort correctly — but a whole `Double` renders as `"1.0"`, and every
     * screen that interpolated the raw value showed "Book 1.0" until someone noticed. Formatting
     * once, here, is what makes forgetting impossible rather than merely unlikely; it is the shape
     * the iOS client has always used.
     */
    val sequenceLabel: String? get() = sequence?.let(::formatSeriesSequence)
}
