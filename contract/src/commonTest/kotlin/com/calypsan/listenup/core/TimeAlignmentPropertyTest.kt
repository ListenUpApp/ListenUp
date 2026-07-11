package com.calypsan.listenup.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeStrictlyIncreasing
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.checkAll

/** Generates 2..6 valid anchors: strictly increasing source and target within a 65h book. */
private val validAnchorsArb: Arb<List<TimeAnchor>> =
    Arb.list(Arb.long(0L..234_000_000L), 2..6).map { sources ->
        val s = sources.distinct().sorted()
        if (s.size < 2) {
            listOf(TimeAnchor(0L, 1L), TimeAnchor(2L, 3L))
        } else {
            s.mapIndexed { i, src ->
                // Target = source shifted by a per-index growing offset — always strictly increasing.
                TimeAnchor(sourceMs = src, targetMs = src + i * 1_500L)
            }
        }
    }

class TimeAlignmentPropertyTest :
    FunSpec({

        test("property: valid anchors always validate") {
            checkAll(validAnchorsArb) { anchors ->
                validateAnchors(anchors) shouldBe AnchorValidation.Valid
            }
        }

        test("property: strictly increasing input stays strictly increasing") {
            checkAll(validAnchorsArb, Arb.list(Arb.long(0L..234_000_000L), 2..50)) { anchors, raw ->
                val input = raw.distinct().sorted()
                if (input.size >= 2) {
                    alignTimestamps(anchors, input).shouldBeStrictlyIncreasing()
                }
            }
        }

        test("property: zero-offset anchors are the identity") {
            checkAll(Arb.list(Arb.long(0L..234_000_000L), 1..40)) { raw ->
                val anchors = listOf(TimeAnchor(0L, 0L), TimeAnchor(234_000_000L, 234_000_000L))
                alignTimestamps(anchors, raw) shouldBe raw
            }
        }

        test("property: output size and order match input") {
            checkAll(validAnchorsArb, Arb.list(Arb.long(0L..234_000_000L), 0..40)) { anchors, input ->
                val out = alignTimestamps(anchors, input)
                out.size shouldBe input.size
            }
        }

        test("scale: 300 chapters over a 65-hour book align in one pass") {
            // 65h ≈ 234_000_000 ms; 300 evenly spaced chapter starts; drift growing to +48s.
            val anchors = listOf(TimeAnchor(0L, 3_200L), TimeAnchor(234_000_000L, 234_048_000L))
            val chapters = List(300) { it * 780_000L }
            val result = alignTimestamps(anchors, chapters)
            result.size shouldBe 300
            result.shouldBeStrictlyIncreasing()
            result.first() shouldBe 3_200L
        }

        test("property: anchor sources always map exactly to their targets") {
            checkAll(validAnchorsArb) { anchors ->
                alignTimestamps(anchors, anchors.map { it.sourceMs }) shouldBe anchors.map { it.targetMs }
            }
        }
    })
