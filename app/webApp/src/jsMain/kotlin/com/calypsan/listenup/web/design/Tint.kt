package com.calypsan.listenup.web.design

import kotlin.math.abs

/**
 * A stable colour derived from [seed] — the shared "hash the string, derive a hue, offset a
 * second stop off it" trick behind [Cover]'s title-derived gradient and the Contributors page's
 * avatar tint. One implementation rather than two copies of the same hash math drifting apart.
 *
 * The angle and the two hsl stops are the caller's own mood — Cover's real-artwork fallback and
 * the avatar tint each want a different one (the avatar tuned darker and more saturated so
 * two-letter initials stay legible against it) — so only the seed-to-hue math is shared.
 */
internal fun tintGradient(
    seed: String,
    angleDegrees: Int,
    firstSaturation: Int,
    firstLightness: Int,
    secondSaturation: Int,
    secondLightness: Int,
): String {
    val hue = hueFrom(seed)
    val second = (hue + HUE_SPREAD) % HUE_RANGE
    return "linear-gradient(${angleDegrees}deg, hsl($hue $firstSaturation% $firstLightness%), " +
        "hsl($second $secondSaturation% $secondLightness%))"
}

/**
 * A stable hue in `0 until HUE_RANGE`, derived from [seed]'s hash.
 *
 * [abs] does the right thing for every ordinary hash code — it is only [Int.MIN_VALUE] where
 * two's-complement overflow makes `abs` return a negative number, which would in turn make this
 * function return a negative hue. [Int.MAX_VALUE] is the fallback for exactly that one value;
 * every other seed's hue is byte-for-byte what plain `abs(seed.hashCode()) % HUE_RANGE` already
 * produced, so no existing cover's colour moves.
 */
internal fun hueFrom(seed: String): Int {
    val hash = seed.hashCode()
    val magnitude = if (hash == Int.MIN_VALUE) Int.MAX_VALUE else abs(hash)
    return magnitude % HUE_RANGE
}

internal const val HUE_RANGE = 360

internal const val HUE_SPREAD = 24
