package com.calypsan.listenup.client.features.nowplaying

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.Locale

/**
 * How this UI writes a speed. The rounding it draws from (`snapPlaybackSpeed`) is commonMain's and
 * is covered there — what is left here is purely how the number is spelled on a pill.
 *
 * In `androidHostTest` rather than beside the desktop specs, because that is the sharedUI source
 * set CI actually runs: `ci.yml` invokes `:app:sharedUI:testAndroidHostTest` and never
 * `:app:sharedUI:desktopTest`, so a spec placed there is neither run nor compiled on a PR. Nothing
 * here needs a desktop.
 */
class PlaybackSpeedLabelTest :
    FunSpec({
        test("a whole speed keeps its decimal point rather than reading as a different value") {
            // "1x" beside "1.25x" reads as a different kind of value, and a column of pills is
            // easier to scan when every label has a point in it.
            formatPlaybackSpeed(1.0f) shouldBe "1.0x"
            formatPlaybackSpeed(2.0f) shouldBe "2.0x"
        }

        test("a fractional speed drops trailing zeros") {
            formatPlaybackSpeed(1.25f) shouldBe "1.25x"
            formatPlaybackSpeed(1.5f) shouldBe "1.5x"
            formatPlaybackSpeed(0.75f) shouldBe "0.75x"
        }

        test("a fractional speed keeps its decimal point in a comma-decimal locale") {
            // The whole-speed branch always writes a point; the fractional one must match it, or a
            // German phone shows "1.0x" beside "1,25x" in the same column of pills.
            val original = Locale.getDefault()
            try {
                Locale.setDefault(Locale.GERMANY)
                formatPlaybackSpeed(1.25f) shouldBe "1.25x"
                formatPlaybackSpeed(1.5f) shouldBe "1.5x"
            } finally {
                Locale.setDefault(original)
            }
        }
    })
