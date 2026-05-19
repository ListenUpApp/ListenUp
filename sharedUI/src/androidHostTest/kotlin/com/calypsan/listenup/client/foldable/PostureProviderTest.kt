package com.calypsan.listenup.client.foldable

import androidx.window.layout.FoldingFeature
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [classifyPosture].
 *
 * [classifyPosture] is pure — it accepts already-extracted [FoldingFeature.State] and
 * [FoldingFeature.Orientation] values (public constants) and returns a [Posture]. No Android
 * framework or Robolectric is needed; the test runs on the JVM directly as a plain Kotest spec.
 *
 * The `null`-input branches cover the case where no [FoldingFeature] is present in the display
 * features list — [posture] passes `null, null` in that case, so [classifyPosture] must handle
 * it gracefully and return [Posture.NORMAL].
 */
class PostureProviderTest :
    FunSpec({
        // ── HALF_OPENED variants ──────────────────────────────────────────────

        test("HALF_OPENED + HORIZONTAL yields TABLETOP") {
            classifyPosture(
                FoldingFeature.State.HALF_OPENED,
                FoldingFeature.Orientation.HORIZONTAL,
            ) shouldBe Posture.TABLETOP
        }

        test("HALF_OPENED + VERTICAL yields BOOK") {
            classifyPosture(
                FoldingFeature.State.HALF_OPENED,
                FoldingFeature.Orientation.VERTICAL,
            ) shouldBe Posture.BOOK
        }

        test("HALF_OPENED + null orientation yields NORMAL") {
            classifyPosture(
                FoldingFeature.State.HALF_OPENED,
                orientation = null,
            ) shouldBe Posture.NORMAL
        }

        // ── FLAT variants ─────────────────────────────────────────────────────

        test("FLAT + HORIZONTAL yields NORMAL") {
            classifyPosture(
                FoldingFeature.State.FLAT,
                FoldingFeature.Orientation.HORIZONTAL,
            ) shouldBe Posture.NORMAL
        }

        test("FLAT + VERTICAL yields NORMAL") {
            classifyPosture(
                FoldingFeature.State.FLAT,
                FoldingFeature.Orientation.VERTICAL,
            ) shouldBe Posture.NORMAL
        }

        // ── No folding feature present ────────────────────────────────────────

        test("null state yields NORMAL (no folding feature present)") {
            classifyPosture(state = null, orientation = null) shouldBe Posture.NORMAL
        }
    })
