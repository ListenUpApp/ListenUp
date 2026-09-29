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

/** Audible 1,007 + Hardcover 58 — the addendum's worked example, less the Goodreads row it once had. */
private val twoCatalogs =
    listOf(
        rating(average = 4.7, count = 1_007, source = AUDIBLE),
        rating(average = 4.26, count = 58, source = HARDCOVER),
    )

private val priors = SourceCalibration.PRIORS

/** The default priors' reference mean: (4.40 + 3.95) / 2. */
private const val REFERENCE_MEAN = 4.175

/** The default priors' reference spread: (0.30 + 0.35) / 2. */
private const val REFERENCE_SPREAD = 0.325

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
            // (4.40 + 3.95) / 2 and (0.30 + 0.35) / 2 — Audible and Hardcover, listeners excluded.
            priors.referenceCurve.mean shouldBe (REFERENCE_MEAN plusOrMinus 1e-9)
            priors.referenceCurve.spread shouldBe (REFERENCE_SPREAD plusOrMinus 1e-9)
        }

        test("an exactly-average book scores the reference mean, whichever catalog rated it") {
            val audibleOnly = listenUpScore(listOf(rating(4.40, 100_000, AUDIBLE)), null, priors)
            val hardcoverOnly = listenUpScore(listOf(rating(3.95, 100_000, HARDCOVER)), null, priors)

            audibleOnly.shouldNotBeNull().average shouldBe (priors.referenceCurve.mean plusOrMinus 1e-9)
            hardcoverOnly.shouldNotBeNull().average shouldBe (priors.referenceCurve.mean plusOrMinus 1e-9)
        }

        test("one source scores its shrunk average's place on its own curve, read off the reference curve") {
            val result = listenUpScore(listOf(rating(4.7, 1_007)), listeners = null, calibration = priors)

            val shrunk = (4.7 * 1_007 + 4.40 * 25) / 1_032
            result.shouldNotBeNull()
            result.average shouldBe (REFERENCE_MEAN + REFERENCE_SPREAD * (shrunk - 4.40) / 0.30 plusOrMinus 1e-9)
            result.count shouldBe 1_007
            result.shares shouldBe mapOf(ScoreSource.Outside(AUDIBLE) to 1.0)
        }

        test("any single outside source lands where its z-score says on the reference curve") {
            checkAll(averageArb, Arb.int(1, 100_000)) { average, count ->
                val result = listenUpScore(listOf(rating(average, count, HARDCOVER)), null, priors)
                val shrunk = (average * count + 3.95 * 25) / (count + 25)
                val expected = REFERENCE_MEAN + REFERENCE_SPREAD * (shrunk - 3.95) / 0.35
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
                    listOf(rating(4.7, 1_007, AUDIBLE), rating(4.2, 100_000, HARDCOVER)),
                    null,
                    priors,
                )

            result.shouldNotBeNull()
            // A hypothetical 100,000-rating Hardcover row: count-weighted, it would be 0.99 of the score.
            result.shares.getValue(ScoreSource.Outside(HARDCOVER)) shouldBeLessThan 0.65
        }

        test("the same raw 4.3 lifts the score more from a harsh curve than a generous one") {
            val base = listeners(4.0, 3)
            val fromHardcover = listenUpScore(listOf(rating(4.3, 500, HARDCOVER)), base, priors)
            val fromAudible = listenUpScore(listOf(rating(4.3, 500, AUDIBLE)), base, priors)

            fromHardcover.shouldNotBeNull()
            fromAudible.shouldNotBeNull()
            // 4.3 is above Hardcover's curve and below Audible's: a clear gap, not a rounding one.
            fromHardcover.average - fromAudible.average shouldBeGreaterThan 0.05
        }

        test("the score always lies in 1..5 and the shares always sum to one") {
            // Every wire source but UNKNOWN (filtered out upstream) — GOODREADS included, on its neutral curve.
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

        test("a row from a source with no prior scores on a neutral curve and leaves the reference alone") {
            // GOODREADS stays on the wire (clients decode it) but no server fetches it and it has no prior.
            val result = listenUpScore(twoCatalogs + rating(4.2, 100_000, GOODREADS), null, priors)
            val calibration =
                SourceCalibration.from(outside = List(30) { rating(3.0, 500, GOODREADS) }, listeners = emptyList())

            result.shouldNotBeNull()
            result.average shouldBeGreaterThanOrEqual 1.0
            result.average shouldBeLessThanOrEqual 5.0
            result.shares.keys shouldBe
                setOf(ScoreSource.Outside(AUDIBLE), ScoreSource.Outside(HARDCOVER), ScoreSource.Outside(GOODREADS))
            priors.curveOf(ScoreSource.Outside(GOODREADS)) shouldBe SourceCurve(mean = 4.0, spread = 0.35)
            calibration.referenceCurve shouldBe priors.referenceCurve
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
                SourceCalibration.from(outside = List(30) { rating(4.6, 500, AUDIBLE) }, listeners = emptyList())

            val audible = calibration.curveOf(ScoreSource.Outside(AUDIBLE))
            // Zero Hardcover rows: Hardcover still sits in the mean, at its prior (3.95, 0.35).
            calibration.referenceCurve.mean shouldBe ((audible.mean + 3.95) / 2 plusOrMinus 1e-9)
            calibration.referenceCurve.spread shouldBe ((audible.spread + 0.35) / 2 plusOrMinus 1e-9)
        }

        test("listeners never move the reference curve") {
            val calibration = SourceCalibration.from(outside = emptyList(), listeners = List(30) { listeners(5.0, 3) })

            calibration.referenceCurve shouldBe priors.referenceCurve
        }

        test("a source with no books in the library keeps its prior") {
            val calibration =
                SourceCalibration.from(outside = List(30) { rating(4.6, 500, AUDIBLE) }, listeners = emptyList())

            calibration.curveOf(ScoreSource.Outside(HARDCOVER)) shouldBe SourceCurve(mean = 3.95, spread = 0.35)
            calibration.curveOf(ScoreSource.Listeners) shouldBe SourceCurve(mean = 4.0, spread = 0.6)
        }

        test("the library's listener averages calibrate the listeners' curve") {
            val calibration =
                SourceCalibration.from(outside = emptyList(), listeners = List(30) { listeners(4.5, 2) })

            calibration.curveOf(ScoreSource.Listeners).mean shouldBe (4.25 plusOrMinus 1e-9)
        }

        test("your listeners weigh in: one is about 20% of the score, three 34%, ten 47%") {
            // 4·ln(1 + n) against Audible's ln(1,008) and Hardcover's ln(59).
            mapOf(1 to 0.20, 3 to 0.34, 10 to 0.47).forEach { (count, expected) ->
                val result = listenUpScore(twoCatalogs, listeners(4.0, count), priors)

                result.shouldNotBeNull()
                result.shares.getValue(ScoreSource.Listeners) shouldBe (expected plusOrMinus 0.01)
                result.sourceCount shouldBe 3
                result.count shouldBe 1_007 + 58 + count
            }
        }

        test("a listener's five stars move the score more than a Hardcover reader's") {
            val audibleOnly = twoCatalogs.filter { it.source == AUDIBLE }
            val withListeners = listenUpScore(audibleOnly, listeners(5.0, 3), priors)
            val withHardcover = listenUpScore(audibleOnly + rating(5.0, 3, HARDCOVER), null, priors)

            withListeners.shouldNotBeNull()
            withHardcover.shouldNotBeNull()
            withListeners.average shouldBeGreaterThan withHardcover.average
        }

        test("a single trusted listener nearly stands on their own") {
            val result = listenUpScore(emptyList(), listeners(5.0, 1), priors)

            result.shouldNotBeNull()
            // Shrunk to (5.0 · 1 + 4.0 · 1) / 2 = 4.5 — a pseudo-count of one, not twenty-five — then
            // read off the reference curve through the listeners' own curve (4.0, 0.6).
            result.average shouldBe (REFERENCE_MEAN + REFERENCE_SPREAD * (4.5 - 4.0) / 0.6 plusOrMinus 1e-9)
            result.shares.keys shouldBe setOf(ScoreSource.Listeners)
        }

        test("a score only your listeners gave is listeners-only; one an outside source joins is not") {
            listenUpScore(emptyList(), listeners(4.0, 3), priors).shouldNotBeNull().isListenersOnly shouldBe true
            listenUpScore(twoCatalogs, listeners(4.0, 3), priors).shouldNotBeNull().isListenersOnly shouldBe false
            listenUpScore(twoCatalogs, null, priors).shouldNotBeNull().isListenersOnly shouldBe false
        }

        test("shares name every contributing source and skip one with no ratings") {
            val result =
                listenUpScore(
                    twoCatalogs + rating(4.0, 0, ExternalRatingSource.UNKNOWN),
                    listeners(4.0, 3),
                    priors,
                )

            result.shouldNotBeNull()
            result.shares.keys shouldContainExactlyInAnyOrder
                listOf(
                    ScoreSource.Outside(AUDIBLE),
                    ScoreSource.Outside(HARDCOVER),
                    ScoreSource.Listeners,
                )
        }

        test("the shares read the same per catalog, the shape Swift can bridge") {
            val result = listenUpScore(twoCatalogs, listeners(4.0, 3), priors).shouldNotBeNull()

            result.outsideShares shouldContainExactlyInAnyOrder
                listOf(AUDIBLE, HARDCOVER).map {
                    OutsideShare(source = it, share = result.shares.getValue(ScoreSource.Outside(it)))
                }
            result.listenersShare shouldBe result.shares.getValue(ScoreSource.Listeners)
            listenUpScore(twoCatalogs, null, priors).shouldNotBeNull().listenersShare.shouldBeNull()
        }

        test("a score built from per-catalog shares is the score they came from") {
            val result = listenUpScore(twoCatalogs, listeners(4.0, 3), priors).shouldNotBeNull()

            CombinedScore(result.average, result.count, result.outsideShares, result.listenersShare) shouldBe result
        }
    })
