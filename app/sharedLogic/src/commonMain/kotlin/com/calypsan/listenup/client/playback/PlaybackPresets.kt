package com.calypsan.listenup.client.playback

import com.calypsan.listenup.domain.VolumeBoostLimits
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What the speed, skip and boost pickers offer, and how a speed is written, decided once so Android, iOS and web list
 * the same choices in the same order and spell a rate the same way: a listener who sets 90 seconds on one device finds
 * 90 seconds, not a blank picker, on the next. An object, so Swift sees `PlaybackPresets.shared`.
 */
object PlaybackPresets {
    /** Slack allowed when matching a speed to a rung: a speed that went through storage is a hair off its rung. */
    private const val SPEED_MATCH_TOLERANCE = 0.01f

    /** The speeds every picker offers, slowest first. */
    val SPEEDS: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

    /**
     * The skip-forward choices, in seconds. Two lists rather than one because the directions are used differently:
     * going on is a longer jump than catching a missed sentence.
     */
    val SKIP_FORWARD_SECONDS: List<Int> = listOf(10, 15, 20, 30, 45, 60, 90, 120)

    /** The skip-back choices, in seconds. */
    val SKIP_BACKWARD_SECONDS: List<Int> = listOf(5, 10, 15, 20, 30, 45, 60)

    /** The volume-boost choices in decibels, re-exposed here because `:contract` is not part of the Swift surface. */
    val BOOST_DB: List<Float> = VolumeBoostLimits.PRESETS_DB

    /**
     * A speed as every surface writes it: `1.0×`, `1.25×`, `0.5×` — a decimal point always, at most two decimals,
     * and the multiplication sign. The column of presets is easier to scan when every label has a decimal point.
     * Only a control too narrow for this may show [speedNumber] alone, built from the same number.
     */
    fun speedLabel(speed: Float): String = "${speedNumber(speed)}×"

    /**
     * The number alone (`1.25`), also what a screen reader speaks in "1.25 times", so it reads what the label shows.
     * Plain arithmetic, not `String.format`: commonMain cannot reach `java.util`, and a locale-aware format puts a
     * comma in "1,25" beside "1.0" on a comma-decimal phone.
     */
    fun speedNumber(speed: Float): String {
        val hundredths = (speed * 100).roundToInt()
        val fraction =
            (hundredths % 100)
                .toString()
                .padStart(2, '0')
                .trimEnd('0')
                .ifEmpty { "0" }
        return "${hundredths / 100}.$fraction"
    }

    /**
     * [SPEEDS] with [current] added in order when it is not already a rung, so a speed set elsewhere is a checked row
     * in the picker, never a blank one.
     */
    fun speedChoicesIncluding(current: Float): List<Float> =
        choicesIncluding(SPEEDS, current) { a, b -> abs(a - b) < SPEED_MATCH_TOLERANCE }

    /** [SKIP_FORWARD_SECONDS] with [current] added in order when it is not already a choice. */
    fun skipForwardChoicesIncluding(current: Int): List<Int> =
        choicesIncluding(SKIP_FORWARD_SECONDS, current) { a, b -> a == b }

    /** [SKIP_BACKWARD_SECONDS] with [current] added in order when it is not already a choice. */
    fun skipBackwardChoicesIncluding(current: Int): List<Int> =
        choicesIncluding(SKIP_BACKWARD_SECONDS, current) { a, b -> a == b }

    /**
     * The speed one press of a car's rate button lands on: the next rung above [current], wrapping from the fastest to
     * the slowest. One catalogue feeds every surface, so CarPlay and Android Auto cycle the same ladder.
     */
    fun nextSpeedInCycle(current: Float): Float =
        SPEEDS.firstOrNull { it > current + SPEED_MATCH_TOLERANCE } ?: SPEEDS.first()

    private fun <T : Comparable<T>> choicesIncluding(
        choices: List<T>,
        current: T,
        same: (T, T) -> Boolean,
    ): List<T> = if (choices.any { same(it, current) }) choices else (choices + current).sorted()
}
