package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.BookId
import kotlin.math.abs

/**
 * The audibility floor for re-persisting a loudness measurement, in dB.
 *
 * 0.1 dB is well below what anyone can hear. Movement smaller than that is the meter settling as
 * its integration window grows, not a refinement worth a sync round-trip.
 */
private const val MEASUREMENT_JITTER_DB = 0.1f

/**
 * Whether a freshly read R128 measurement is worth persisting against [bookId].
 *
 * Two ways to answer no. The reading has not moved: the meter refines continuously while a book
 * plays, so every pause offers a slightly different one. The first always saves — it is the only
 * reading the next session has to go on — and later ones only once they have audibly moved.
 *
 * Or the meter is not measuring [bookId] at all. [meterBookId] is the book the meter was last
 * scoped to, so it lags a book change by however long the reset takes to run; a pause landing in
 * that window reads the outgoing book's loudness and would file it under the incoming book's id.
 * Comparing the *meter's* book rather than re-reading the same source [bookId] came from is what
 * makes this a real guard instead of a tautology.
 *
 * Mirrors the iOS guard in `PlayerCoordinator+Gain.swift`. The two must agree, or the same book
 * would accumulate a different number of measurement writes depending on which device played it.
 */
internal fun shouldSaveMeasurement(
    measured: Float,
    lastSaved: Float?,
    meterBookId: BookId?,
    bookId: BookId,
): Boolean {
    if (meterBookId != bookId) return false
    return lastSaved == null || abs(measured - lastSaved) > MEASUREMENT_JITTER_DB
}
