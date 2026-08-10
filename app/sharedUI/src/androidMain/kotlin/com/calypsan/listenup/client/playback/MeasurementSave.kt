package com.calypsan.listenup.client.playback

import kotlin.math.abs

/**
 * The audibility floor for re-persisting a loudness measurement, in dB.
 *
 * 0.1 dB is well below what anyone can hear. Movement smaller than that is the meter settling as
 * its integration window grows, not a refinement worth a sync round-trip.
 */
private const val MEASUREMENT_JITTER_DB = 0.1f

/**
 * Whether a freshly read R128 measurement is worth persisting.
 *
 * The meter refines continuously while a book plays, so every pause offers a slightly different
 * reading. The first one always saves — it is the only reading the next session has to go on — and
 * later ones only once they have actually moved.
 *
 * Mirrors the iOS guard in `PlayerCoordinator+Gain.swift`. The two must agree, or the same book
 * would accumulate a different number of measurement writes depending on which device played it.
 */
internal fun shouldSaveMeasurement(
    measured: Float,
    lastSaved: Float?,
): Boolean = lastSaved == null || abs(measured - lastSaved) > MEASUREMENT_JITTER_DB
