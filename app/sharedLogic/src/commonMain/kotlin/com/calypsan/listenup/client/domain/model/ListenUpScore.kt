package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.sync.ExternalRatingSource
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * One source the ListenUp score draws on: an outside catalog, or this server's own listeners.
 *
 * A client-side key rather than a new [ExternalRatingSource] value: that enum crosses the wire and
 * names the server's rows, and "your listeners" is neither — it is computed on the device from
 * `book_ratings`. Keeping it out of the wire enum keeps the contract honest.
 */
sealed interface ScoreSource {
    /** An outside catalog's rating, one row per book synced from the server. */
    data class Outside(
        val source: ExternalRatingSource,
    ) : ScoreSource

    /** Every listener on this server who rated the book — the same for every member. */
    data object Listeners : ScoreSource
}

/**
 * The curve one source rates on: the [mean] and [spread] (standard deviation) of its averages.
 * Audible listeners run generous and Hardcover readers harsh; the curve is what puts them level.
 */
internal data class SourceCurve(
    val mean: Double,
    val spread: Double,
)

/**
 * Each source's [SourceCurve], learned from the library's own ratings and blended with a fixed
 * prior while the library is small: `curve = (k·library + K·prior) / (k + K)` for both mean and
 * spread, where k is how many books the source rated and K = [PRIOR_WEIGHT].
 */
internal class SourceCalibration private constructor(
    private val curves: Map<ScoreSource, SourceCurve>,
) {
    /** [source]'s calibrated curve; its prior when the library has none of its ratings. */
    fun curveOf(source: ScoreSource): SourceCurve = curves[source] ?: priorOf(source)

    /**
     * The one scale every book is read off: the unweighted mean of the calibrated curves of every
     * outside catalog ListenUp knows, enabled or not, listeners excluded. A catalog with no rows
     * contributes its prior. It is a scale, not evidence: it needs no enabled set (which a client
     * cannot see for a source with no rows) and does not jump when a source lands its first row.
     */
    val referenceCurve: SourceCurve =
        OUTSIDE_PRIORS.keys.map { curveOf(ScoreSource.Outside(it)) }.let { known ->
            SourceCurve(mean = known.map { it.mean }.average(), spread = known.map { it.spread }.average())
        }

    /** The priors and the calibration-from-library factory. */
    companion object {
        /** How many books' worth of weight the prior carries against the library's own averages. */
        const val PRIOR_WEIGHT: Double = 30.0

        /**
         * Every outside catalog ListenUp knows, with its starting curve — to revisit with real data
         * (design addendum 2026-09-29). Its keys are the set [referenceCurve] averages over.
         */
        private val OUTSIDE_PRIORS: Map<ExternalRatingSource, SourceCurve> =
            mapOf(
                ExternalRatingSource.AUDIBLE to SourceCurve(mean = 4.40, spread = 0.30),
                ExternalRatingSource.HARDCOVER to SourceCurve(mean = 3.95, spread = 0.35),
            )

        private val LISTENER_PRIOR = SourceCurve(mean = 4.0, spread = 0.6)

        /**
         * The curve of a source with no prior: `UNKNOWN` (filtered out before scoring) or a wire value
         * no server fetches any more, like `GOODREADS`. A neutral curve keeps [priorOf] total, so such a
         * row scores instead of failing — and, not being in [OUTSIDE_PRIORS], never moves [referenceCurve].
         */
        private val UNKNOWN_PRIOR = SourceCurve(mean = 4.0, spread = 0.35)

        /** The calibration of an empty library: every source on its prior. */
        val PRIORS: SourceCalibration = SourceCalibration(emptyMap())

        /**
         * Learns each source's curve from every book's [outside] ratings and every book's
         * [listeners] average. Sources with no ratings (count 0) teach nothing.
         */
        fun from(
            outside: List<ExternalRating>,
            listeners: List<ListenerAverage>,
        ): SourceCalibration {
            val outsideSamples: Map<ScoreSource, List<Double>> =
                outside
                    .filter { it.count > 0 }
                    .groupBy(keySelector = { ScoreSource.Outside(it.source) }, valueTransform = { it.average })
            val listenerSamples = listeners.filter { it.count > 0 }.map { it.stars }
            val samples =
                if (listenerSamples.isEmpty()) {
                    outsideSamples
                } else {
                    outsideSamples +
                        (ScoreSource.Listeners to listenerSamples)
                }
            return SourceCalibration(samples.mapValues { (source, averages) -> blend(averages, priorOf(source)) })
        }

        private fun blend(
            averages: List<Double>,
            prior: SourceCurve,
        ): SourceCurve {
            val books = averages.size.toDouble()
            val mean = averages.average()
            val spread = sqrt(averages.sumOf { (it - mean) * (it - mean) } / books)
            return SourceCurve(
                mean = (books * mean + PRIOR_WEIGHT * prior.mean) / (books + PRIOR_WEIGHT),
                spread = (books * spread + PRIOR_WEIGHT * prior.spread) / (books + PRIOR_WEIGHT),
            )
        }

        private fun priorOf(source: ScoreSource): SourceCurve =
            when (source) {
                ScoreSource.Listeners -> LISTENER_PRIOR
                is ScoreSource.Outside -> OUTSIDE_PRIORS[source.source] ?: UNKNOWN_PRIOR
            }
    }
}

/** Pseudo-count an outside catalog's average is shrunk toward its curve with. */
private const val OUTSIDE_PSEUDO_COUNT = 25.0

/** Pseudo-count a listener average is shrunk with: a trusted rating nearly stands on its own. */
private const val LISTENER_PSEUDO_COUNT = 1.0

/** How much louder this server's listeners are than an outside catalog of the same size. */
private const val LISTENER_BOOST = 4.0

/** Floor on a curve's spread, so a library where a source always says the same thing can't divide by ~0. */
private const val MIN_SPREAD = 0.05

private const val MIN_SCORE = 1.0
private const val MAX_SCORE = 5.0

/** A listener average in stars (Room stores half stars). */
private val ListenerAverage.stars: Double get() = averageHalfStars / 2

/**
 * The ListenUp score of one book (design addendum 2026-09-29), pure and computed on the device.
 *
 * For each source with n > 0 ratings averaging a, on its calibrated curve (μ, σ):
 * 1. **shrink** small samples toward the curve: `(a·n + μ·p) / (n + p)`, p = 25 outside, 1 for listeners;
 * 2. put it on **one curve**: `z = (shrunk − μ) / max(σ, 0.05)`;
 * 3. **weigh** it by `ln(1 + n)`, times 4 for listeners — so no catalog drowns the rest by volume;
 * 4. **combine**: `μ_ref + σ_ref · Σ(w·z) / Σw`, clamped to 1..5, where (μ_ref, σ_ref) is the
 *    calibration's [SourceCalibration.referenceCurve] — one scale for every book, so an exactly-average
 *    book scores μ_ref whichever catalog rated it.
 *
 * A one-source book scores its shrunk average's place on that source's curve, read off the
 * reference curve: an Audible-only 4.7 shows about 4.5, while its breakdown row still says 4.7.
 * Every input is server-wide — no
 * signed-in user enters — so every member sees the same score for the same book.
 *
 * Callers pass only enabled, known sources; the repository filters the rest out.
 *
 * @return null when no source has any ratings.
 */
internal fun listenUpScore(
    outside: List<ExternalRating>,
    listeners: ListenerAverage?,
    calibration: SourceCalibration,
): CombinedScore? {
    val contributions =
        outside.filter { it.count > 0 }.map { rating ->
            contribution(
                source = ScoreSource.Outside(rating.source),
                average = rating.average,
                count = rating.count,
                pseudoCount = OUTSIDE_PSEUDO_COUNT,
                boost = 1.0,
                calibration = calibration,
            )
        } +
            listOfNotNull(
                listeners?.takeIf { it.count > 0 }?.let { listenerAverage ->
                    contribution(
                        source = ScoreSource.Listeners,
                        average = listenerAverage.stars,
                        count = listenerAverage.count,
                        pseudoCount = LISTENER_PSEUDO_COUNT,
                        boost = LISTENER_BOOST,
                        calibration = calibration,
                    )
                },
            )
    if (contributions.isEmpty()) return null
    val totalWeight = contributions.sumOf { it.weight }
    val reference = calibration.referenceCurve
    val meanZ = contributions.sumOf { it.weight * it.z } / totalWeight
    return CombinedScore(
        average = (reference.mean + reference.spread * meanZ).coerceIn(MIN_SCORE, MAX_SCORE),
        count = contributions.sumOf { it.count.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        shares = contributions.associate { it.source to it.weight / totalWeight },
    )
}

private data class Contribution(
    val source: ScoreSource,
    val count: Int,
    val z: Double,
    val weight: Double,
)

private fun contribution(
    source: ScoreSource,
    average: Double,
    count: Int,
    pseudoCount: Double,
    boost: Double,
    calibration: SourceCalibration,
): Contribution {
    val curve = calibration.curveOf(source)
    val shrunk = (average * count + curve.mean * pseudoCount) / (count + pseudoCount)
    return Contribution(
        source = source,
        count = count,
        z = (shrunk - curve.mean) / maxOf(curve.spread, MIN_SPREAD),
        weight = boost * ln(1.0 + count),
    )
}
