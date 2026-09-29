@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.calypsan.listenup.client.domain.model

import com.calypsan.listenup.api.sync.ExternalRatingSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
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
import io.kotest.property.checkAll

/** 0.0..5.0, NaN excluded — Kotest's bounded [Arb.double] still edge-cases NaN in, which no rating average can be. */
private val averageArb = Arb.double(0.0, 5.0).filter { !it.isNaN() }

private fun rating(
    average: Double,
    count: Int,
    source: ExternalRatingSource = ExternalRatingSource.AUDIBLE,
) = ExternalRating(source = source, average = average, count = count)

/**
 * [combineExternalRatings] is the headline weighting: every enabled source's average, weighted by
 * how many ratings each is over. Property-tested because the invariant ("the result never leaves
 * the min/max average") is what a fixed example can't pin down on its own.
 */
class ExternalRatingTest :
    FunSpec({
        test("a single source equals itself") {
            // A tolerance, not exact equality: average*count/count round-trips through Double
            // multiplication and division, which isn't bit-exact for an arbitrary count.
            checkAll(averageArb, Arb.int(1, 100_000)) { average, count ->
                val result = combineExternalRatings(listOf(rating(average, count)))
                result.shouldNotBeNull()
                result.average shouldBe (average plusOrMinus 1e-9)
                result.count shouldBe count
            }
        }

        test("weighting: 4.0 x100 and 5.0 x300 gives 4.75") {
            val result =
                combineExternalRatings(
                    listOf(
                        rating(average = 4.0, count = 100, source = ExternalRatingSource.AUDIBLE),
                        rating(average = 5.0, count = 300, source = ExternalRatingSource.HARDCOVER),
                    ),
                )
            result.shouldNotBeNull()
            result.average shouldBe 4.75
            result.count shouldBe 400
        }

        test("an empty list gives null") {
            combineExternalRatings(emptyList()).shouldBeNull()
        }

        test("all-zero counts gives null") {
            checkAll(Arb.list(averageArb, 1..5)) { averages ->
                combineExternalRatings(averages.map { rating(it, count = 0) }).shouldBeNull()
            }
        }

        test("the result stays between the minimum and maximum average") {
            val ratingArb = Arb.bind(averageArb, Arb.int(1, 1_000)) { average, count -> rating(average, count) }
            checkAll(Arb.list(ratingArb, 1..8)) { ratings ->
                val result = combineExternalRatings(ratings)
                result.shouldNotBeNull()
                val min = ratings.minOf { it.average }
                val max = ratings.maxOf { it.average }
                // A tiny epsilon absorbs floating-point rounding at the boundary — the invariant
                // being tested is "stays within the spread", not bit-exact equality at an edge.
                result.average shouldBeGreaterThanOrEqual min - 1e-9
                result.average shouldBeLessThanOrEqual max + 1e-9
            }
        }
    })
