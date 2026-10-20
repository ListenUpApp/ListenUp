package com.calypsan.listenup.client.core.color

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeSortedDescendingBy
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/** Spec §2.3 step 1: one extractor on every platform, quantizer clusters plus androidx.palette's six roles. */
class CoverSwatchesTest :
    FunSpec({
        test("a cover with no opaque pixel has no swatches") {
            extractSwatches(IntArray(4), width = 2, height = 2) shouldBe null
        }

        test("pixels under half alpha are ignored") {
            val pixels = intArrayOf(hex("#AEB5C2"), 0x40FF0000, 0x00000000, 0x7F00FF00)
            val swatches = extractSwatches(pixels, width = 2, height = 2).shouldNotBeNull()
            swatches.clusters.size shouldBe 1
            channelDistance(swatches.clusters.single().argb, hex("#AEB5C2")) shouldBeLessThanOrEqual 1
            swatches.totalPopulation shouldBe 1
        }

        test("a one-pixel cover still has a swatch") {
            extractSwatches(intArrayOf(hex("#A9BCAF")), width = 1, height = 1)
                .shouldNotBeNull()
                .lightMuted
                .shouldNotBeNull()
        }

        test("clusters come most populous first and account for every opaque pixel") {
            val swatches = swatchesOf(*WAY_OF_KINGS)
            swatches.clusters.shouldBeSortedDescendingBy { it.population }
            swatches.totalPopulation shouldBe 4096
        }

        test("The Way of Kings: silver is the light muted, gold the light vibrant") {
            val swatches = swatchesOf(*WAY_OF_KINGS)
            channelDistance(swatches.lightMuted.shouldNotBeNull().argb, hex("#AEB5C2")) shouldBeLessThanOrEqual 1
            channelDistance(swatches.lightVibrant.shouldNotBeNull().argb, hex("#E2A159")) shouldBeLessThanOrEqual 1
        }

        test("the green cover: sage is the light muted") {
            channelDistance(swatchesOf(*GREEN).lightMuted.shouldNotBeNull().argb, hex("#A9BCAF")) shouldBeLessThanOrEqual 1
        }

        test("no cluster fills two roles") {
            val s = swatchesOf(*WAY_OF_KINGS)
            val roles = listOfNotNull(s.lightVibrant, s.vibrant, s.darkVibrant, s.lightMuted, s.muted, s.darkMuted)
            roles.size shouldBe roles.toSet().size
        }

        test("a grey cover has no vibrant role") {
            val s = swatchesOf(*GREYSCALE)
            listOfNotNull(s.lightVibrant, s.vibrant, s.darkVibrant) shouldBe emptyList()
        }

        test("a cover with more colours than the budget is quantized down to it") {
            val gradient = IntArray(4096) { i -> (0xFF shl 24) or ((i % 64 * 4) shl 16) or ((i / 64 * 4) shl 8) or 0x80 }
            val swatches = extractSwatches(gradient, width = 64, height = 64).shouldNotBeNull()
            swatches.clusters.size shouldBeLessThanOrEqual 16
            swatches.totalPopulation shouldBe 4096
        }

        test("extraction is deterministic") {
            swatchesOf(*GREEN) shouldBe swatchesOf(*GREEN)
        }
    })
