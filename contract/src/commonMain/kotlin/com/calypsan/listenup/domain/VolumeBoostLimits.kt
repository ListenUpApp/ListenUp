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

    /**
     * The gap between offered boost settings.
     *
     * 3 dB is a doubling of power and the smallest step most listeners hear as a real change on
     * spoken word — finer rungs would offer choices nobody could tell apart.
     */
    const val STEP_DB: Float = 3f

    /** The closed range, for `coerceIn`/validation call sites. */
    val RANGE: ClosedFloatingPointRange<Float> = MIN_DB..MAX_DB

    /**
     * Every boost a listener can pick, in order: `0, 3, 6, 9, 12`.
     *
     * Boost is a small discrete catalogue by design — unlike speed there is no slider anywhere,
     * on any client — so the ladder itself is part of the contract rather than a per-platform
     * rendering choice. It lives beside the bounds it is derived from so a browser cannot offer a
     * rung a phone does not, which is exactly what happened while it was generated in
     * `:app:sharedUI` and hardcoded again in Swift.
     */
    val PRESETS_DB: List<Float> =
        generateSequence(MIN_DB) { it + STEP_DB }
            .takeWhile { it <= MAX_DB }
            .toList()
}
