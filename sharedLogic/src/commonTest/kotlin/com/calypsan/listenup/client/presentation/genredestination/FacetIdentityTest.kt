package com.calypsan.listenup.client.presentation.genredestination

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe

/**
 * Tests for [FacetIdentity].
 *
 * Covers:
 * - [FacetIdentity.hue] is deterministic — same name always yields the same hue, drawn from the
 *   fixed palette (different names may collide on a hue; that's an accepted tradeoff of a
 *   12-entry palette, not a bug).
 * - [FacetIdentity.icon] resolves the first matching regex category, in the documented priority
 *   order, and falls back to [FacetIcon.DEFAULT] when nothing matches.
 */
class FacetIdentityTest :
    FunSpec({

        val palette =
            listOf(
                "#2E5AA0",
                "#B04A66",
                "#8A5A20",
                "#1F7E74",
                "#6E4AA6",
                "#3A6A3A",
                "#C2622A",
                "#3F4658",
                "#4A5A6E",
                "#1F6A74",
                "#A6602E",
                "#5B3A8A",
            )

        test("hue is stable across repeated calls for the same name") {
            val first = FacetIdentity.hue("Fantasy")
            val second = FacetIdentity.hue("Fantasy")
            val third = FacetIdentity.hue("Fantasy")

            first shouldBe second
            second shouldBe third
        }

        test("hue always returns a color from the fixed palette") {
            palette shouldContain FacetIdentity.hue("Fantasy")
            palette shouldContain FacetIdentity.hue("Epic Fantasy")
            palette shouldContain FacetIdentity.hue("Some Very Long Unusual Genre Name")
        }

        test("hue is stable for distinct names even when they collide") {
            // Two different names are allowed to land on the same hue (12-entry palette); what
            // matters is each individual name is stable across repeated calls.
            val nameA = "Business"
            val nameB = "Cooking"
            FacetIdentity.hue(nameA) shouldBe FacetIdentity.hue(nameA)
            FacetIdentity.hue(nameB) shouldBe FacetIdentity.hue(nameB)
        }

        test("icon matches Epic Fantasy to FANTASY") {
            FacetIdentity.icon("Epic Fantasy") shouldBe FacetIcon.FANTASY
        }

        test("icon matches Hard Sci-Fi to SCIFI") {
            FacetIdentity.icon("Hard Sci-Fi") shouldBe FacetIcon.SCIFI
        }

        test("icon falls back to DEFAULT for an unrecognized name") {
            FacetIdentity.icon("Zzz Unknown") shouldBe FacetIcon.DEFAULT
        }

        test("icon respects priority order: Science Fiction resolves to SCIFI, not SCIENCE") {
            FacetIdentity.icon("Science Fiction") shouldBe FacetIcon.SCIFI
        }

        test("icon respects priority order: Historical Fiction resolves to HISTORY, not LITERARY") {
            FacetIdentity.icon("Historical Fiction") shouldBe FacetIcon.HISTORY
        }
    })
