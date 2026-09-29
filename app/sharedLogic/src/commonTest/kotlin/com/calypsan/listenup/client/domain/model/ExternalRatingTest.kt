@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.api.sync.ExternalRatingSource.AUDIBLE
import com.calypsan.listenup.api.sync.ExternalRatingSource.GOODREADS
import com.calypsan.listenup.api.sync.ExternalRatingSource.HARDCOVER
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.subsequence
import io.kotest.property.checkAll
import kotlin.math.abs

/** 0.0..5.0, NaN excluded — Kotest's bounded [Arb.double] still edge-cases NaN in, which no rating average can be. */
private val averageArb = Arb.double(0.0, 5.0).filter { !it.isNaN() }

private fun rating(
    average: Double,
    count: Int,
    source: ExternalRatingSource = AUDIBLE,
) = ExternalRating(source = source, average = average, count = count)

/** A [stars] listener average over [count] listeners, stored the way Room reports it (half stars). */
private fun listeners(
    stars: Double,
    count: Int,
) = ListenerAverage(averageHalfStars = stars * 2, count = count)

/** Audible 1,007 + Hardcover 58 + Goodreads 100,000 — the addendum's worked example. */
private val threeCatalogs =
    listOf(
        rating(average = 4.7, count = 1_007, source = AUDIBLE),
        rating(average = 4.26, count = 58, source = HARDCOVER),
        rating(average = 4.2, count = 100_000, source = GOODREADS),
    )

private val priors = SourceCalibration.PRIORS

/** The default priors' reference spread: (0.30 + 0.35 + 0.30) / 3. */
private const val REFERENCE_SPREAD = 0.95 / 3

/**
 * [listenUpScore] is the headline: every source calibrated to its own curve, small samples shrunk
 * toward that curve, weighted by the log of how many ratings each is over, and put back on one
 * familiar 1–5 scale — with this server's listeners as one more, louder source. Property-tested
 * where the claim is an invariant ("always in range", "shares sum to one") a fixed example can't
 * pin down.
 */
class ExternalRatingTest :
    FunSpec({
        test("every book sits on one curve: the mean of every known outside source's curve") {
            // (4.40 + 3.95 + 3.95) / 3 and (0.30 + 0.35 + 0.30) / 3 — listeners excluded.
            priors.referenceCurve.mean shouldBe (4.10 plusOrMinus 1e-9)
            priors.referenceCurve.spread shouldBe (0.95 / 3 plusOrMinus 1e-9)
        }

        test("an exactly-average book scores the reference mean, whichever catalog rated it") {
            val audibleOnly = listenUpScore(listOf(rating(4.40, 100_000, AUDIBLE)), null, priors)
            val goodreadsOnly = listenUpScore(listOf(rating(3.95, 100_000, GOODREADS)), null, priors)

            audibleOnly.shouldNotBeNull().average shouldBe (priors.referenceCurve.mean plusOrMinus 1e-9)
            goodreadsOnly.shouldNotBeNull().average shouldBe (priors.referenceCurve.mean plusOrMinus 1e-9)
        }

        test("one source scores its shrunk average's place on its own curve, read off the reference curve") {
            val result = listenUpScore(listOf(rating(4.7, 1_007)), listeners = null, calibration = priors)

            val shrunk = (4.7 * 1_007 + 4.40 * 25) / 1_032
            result.shouldNotBeNull()
            result.average shouldBe (4.10 + REFERENCE_SPREAD * (shrunk - 4.40) / 0.30 plusOrMinus 1e-9)
            result.count shouldBe 1_007
            result.shares shouldBe mapOf(ScoreSource.Outside(AUDIBLE) to 1.0)
        }

        test("any single outside source lands where its z-score says on the reference curve") {
            checkAll(averageArb, Arb.int(1, 100_000)) { average, count ->
                val result = listenUpScore(listOf(rating(average, count, GOODREADS)), null, priors)
                val shrunk = (average * count + 3.95 * 25) / (count + 25)
                val expected = 4.10 + REFERENCE_SPREAD * (shrunk - 3.95) / 0.30
                result.shouldNotBeNull()
                result.average shouldBe (expected.coerceIn(1.0, 5.0) plusOrMinus 1e-9)
            }
        }

        test("a tiny sample barely moves off the reference curve") {
            val result = listenUpScore(listOf(rating(5.0, 2, HARDCOVER)), null, priors)

            result.shouldNotBeNull()
            result.average shouldBe (priors.referenceCurve.mean plusOrMinus 0.1)
        }

        test("no source drowns the rest by volume") {
            val result =
                listenUpScore(
                    listOf(rating(4.7, 1_007, AUDIBLE), rating(4.2, 100_000, GOODREADS)),
                    null,
                    priors,
                )

            result.shouldNotBeNull()
            // Count-weighted, Goodreads would be 0.99 of the score.
            result.shares.getValue(ScoreSource.Outside(GOODREADS)) shouldBeLessThan 0.65
        }

        test("the same raw 4.3 lifts the score more from a harsh curve than a generous one") {
            val base = rating(4.0, 100, HARDCOVER)
            val fromGoodreads = listenUpScore(listOf(base, rating(4.3, 500, GOODREADS)), null, priors)
            val fromAudible = listenUpScore(listOf(base, rating(4.3, 500, AUDIBLE)), null, priors)

            fromGoodreads.shouldNotBeNull()
            fromAudible.shouldNotBeNull()
            // 4.3 is above Goodreads' curve and below Audible's: a clear gap, not a rounding one.
            fromGoodreads.average - fromAudible.average shouldBeGreaterThan 0.05
        }

        test("the score always lies in 1..5 and the shares always sum to one") {
            val sourcesArb =
                Arb
                    .subsequence(ExternalRatingSource.entries.filter { it != ExternalRatingSource.UNKNOWN })
                    .filter { it.isNotEmpty() }
            val listenersArb = Arb.bind(Arb.double(1.0, 5.0).filter { !it.isNaN() }, Arb.int(1, 50), ::listeners)
            checkAll(
                sourcesArb,
                Arb.list(averageArb, 3..3),
                Arb.list(Arb.int(1, 1_000_000), 3..3),
                listenersArb.orNull(),
            ) { sources, averages, counts, listenerAverage ->
                val ratings = sources.mapIndexed { i, source -> rating(averages[i], counts[i], source) }
                val result = listenUpScore(ratings, listenerAverage, priors)

                result.shouldNotBeNull()
                result.average shouldBeGreaterThanOrEqual 1.0
                result.average shouldBeLessThanOrEqual 5.0
                abs(result.shares.values.sum() - 1.0) shouldBeLessThan 1e-9
            }
        }

        test("nothing to score gives null") {
            listenUpScore(emptyList(), null, priors).shouldBeNull()
            listenUpScore(emptyList(), listeners(4.0, 0), priors).shouldBeNull()
            checkAll(Arb.list(averageArb, 1..3)) { averages ->
                listenUpScore(averages.map { rating(it, count = 0) }, null, priors).shouldBeNull()
            }
        }

        test("the library's own ratings pull a source's curve toward them") {
            val library = List(30) { rating(4.6, 500, AUDIBLE) }

            val curve =
                SourceCalibration
                    .from(outside = library, listeners = emptyList())
                    .curveOf(ScoreSource.Outside(AUDIBLE))

            // (30 books · 4.6 + 30 prior · 4.40) / 60; spread (30 · 0 + 30 · 0.30) / 60.
            curve.mean shouldBe (4.5 plusOrMinus 1e-9)
            curve.spread shouldBe (0.15 plusOrMinus 1e-9)
        }

        test("the reference curve still counts a catalog with no books in the library, at its prior") {
            val calibration =
                SourceCalibration.from(
                    outside = List(30) { rating(4.6, 500, AUDIBLE) } + List(30) { rating(4.0, 500, GOODREADS) },
                    listeners = emptyList(),
                )

            val audible = calibration.curveOf(ScoreSource.Outside(AUDIBLE))
            val goodreads = calibration.curveOf(ScoreSource.Outside(GOODREADS))
            // Zero Hardcover rows: Hardcover still sits in the mean, at its prior (3.95, 0.35).
            calibration.referenceCurve.mean shouldBe ((audible.mean + 3.95 + goodreads.mean) / 3 plusOrMinus 1e-9)
            calibration.referenceCurve.spread shouldBe ((audible.spread + 0.35 + goodreads.spread) / 3 plusOrMinus 1e-9)
        }

        test("listeners never move the reference curve") {
            val calibration = SourceCalibration.from(outside = emptyList(), listeners = List(30) { listeners(5.0, 3) })

            calibration.referenceCurve shouldBe priors.referenceCurve
        }

        test("a source with no books in the library keeps its prior") {
            val calibration =
                SourceCalibration.from(outside = List(30) { rating(4.6, 500, AUDIBLE) }, listeners = emptyList())

            calibration.curveOf(ScoreSource.Outside(GOODREADS)) shouldBe SourceCurve(mean = 3.95, spread = 0.30)
            calibration.curveOf(ScoreSource.Listeners) shouldBe SourceCurve(mean = 4.0, spread = 0.6)
        }

        test("the library's listener averages calibrate the listeners' curve") {
            val calibration =
                SourceCalibration.from(outside = emptyList(), listeners = List(30) { listeners(4.5, 2) })

            calibration.curveOf(ScoreSource.Listeners).mean shouldBe (4.25 plusOrMinus 1e-9)
        }

        test("your listeners weigh in: one is about 11% of the score, three 20%, ten 30%") {
            mapOf(1 to 0.11, 3 to 0.20, 10 to 0.30).forEach { (count, expected) ->
                val result = listenUpScore(threeCatalogs, listeners(4.0, count), priors)

                result.shouldNotBeNull()
                result.shares.getValue(ScoreSource.Listeners) shouldBe (expected plusOrMinus 0.03)
                result.sourceCount shouldBe 4
                result.count shouldBe 1_007 + 58 + 100_000 + count
            }
        }

        test("a listener's five stars move the score more than a Goodreads reader's") {
            val twoCatalogs = threeCatalogs.filter { it.source != GOODREADS }
            val withListeners = listenUpScore(twoCatalogs, listeners(5.0, 3), priors)
            val withGoodreads = listenUpScore(twoCatalogs + rating(5.0, 3, GOODREADS), null, priors)

            withListeners.shouldNotBeNull()
            withGoodreads.shouldNotBeNull()
            withListeners.average shouldBeGreaterThan withGoodreads.average
        }

        test("a single trusted listener nearly stands on their own") {
            val result = listenUpScore(emptyList(), listeners(5.0, 1), priors)

            result.shouldNotBeNull()
            // Shrunk to (5.0 · 1 + 4.0 · 1) / 2 = 4.5 — a pseudo-count of one, not twenty-five — then
            // read off the reference curve through the listeners' own curve (4.0, 0.6).
            result.average shouldBe (4.10 + REFERENCE_SPREAD * (4.5 - 4.0) / 0.6 plusOrMinus 1e-9)
            result.shares.keys shouldBe setOf(ScoreSource.Listeners)
        }

        test("shares name every contributing source and skip one with no ratings") {
            val result =
                listenUpScore(
                    threeCatalogs + rating(4.0, 0, ExternalRatingSource.UNKNOWN),
                    listeners(4.0, 3),
                    priors,
                )

            result.shouldNotBeNull()
            result.shares.keys shouldContainExactlyInAnyOrder
                listOf(
                    ScoreSource.Outside(AUDIBLE),
                    ScoreSource.Outside(HARDCOVER),
                    ScoreSource.Outside(GOODREADS),
                    ScoreSource.Listeners,
                )
        }
    })
