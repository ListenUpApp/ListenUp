package com.calypsan.listenup.client.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Pins the platform-surface skip icons to the *configured* interval (#1300).
 *
 * Media3 ships numbered skip glyphs for a fixed set of intervals only (5/10/15/30). Our skip
 * presets run to 120 s forward and 60 s back, so a numbered icon is only ever right by
 * coincidence — and "nearest available number" is still a wrong number drawn on a button.
 * The rule here is the same one iOS already applies in `PlayerGlyphs`: an exact match earns the
 * numbered glyph, anything else falls back to the value-neutral one.
 */
@OptIn(UnstableApi::class)
class SkipCommandIconsTest :
    FunSpec({
        test("an interval Media3 has a glyph for gets that glyph") {
            SkipCommandIcons.forward(5) shouldBe CommandButton.ICON_SKIP_FORWARD_5
            SkipCommandIcons.forward(10) shouldBe CommandButton.ICON_SKIP_FORWARD_10
            SkipCommandIcons.forward(15) shouldBe CommandButton.ICON_SKIP_FORWARD_15
            SkipCommandIcons.forward(30) shouldBe CommandButton.ICON_SKIP_FORWARD_30
            SkipCommandIcons.backward(5) shouldBe CommandButton.ICON_SKIP_BACK_5
            SkipCommandIcons.backward(10) shouldBe CommandButton.ICON_SKIP_BACK_10
            SkipCommandIcons.backward(15) shouldBe CommandButton.ICON_SKIP_BACK_15
            SkipCommandIcons.backward(30) shouldBe CommandButton.ICON_SKIP_BACK_30
        }

        test("every other preset falls back to the value-neutral glyph rather than a wrong number") {
            listOf(20, 45, 60, 90, 120).forEach { seconds ->
                SkipCommandIcons.forward(seconds) shouldBe CommandButton.ICON_SKIP_FORWARD
            }
            listOf(20, 45, 60).forEach { seconds ->
                SkipCommandIcons.backward(seconds) shouldBe CommandButton.ICON_SKIP_BACK
            }
        }
    })
