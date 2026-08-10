package com.calypsan.listenup.domain

/**
 * The manual volume-boost range shared by every enforcement point: client gain stages,
 * boost UI steppers, and the server-side preference clamp. Single-sourced here because
 * the server cannot depend on client modules.
 */
object VolumeBoostLimits {
    /** Boost floor: "off". */
    const val MIN_DB: Float = 0f

    /** Boost ceiling. Clipping at the top is accepted by design. */
    const val MAX_DB: Float = 12f

    /** The closed range, for `coerceIn`/validation call sites. */
    val RANGE: ClosedFloatingPointRange<Float> = MIN_DB..MAX_DB
}
