package com.calypsan.listenup.client.playback

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * The graduated auto-rewind ladder: how far playback backs up when you return to a book,
 * as a function of how long you were away. Short breaks cost nothing; the longer the gap,
 * the more re-orientation you need.
 */
class AutoRewindTest :
    FunSpec({

        test("a break under a minute rewinds nothing") {
            autoRewindMs(0) shouldBe 0L
            autoRewindMs(30_000) shouldBe 0L
            autoRewindMs(59_999) shouldBe 0L
        }

        test("a break of minutes to an hour rewinds five seconds") {
            autoRewindMs(60_000) shouldBe 5_000L
            autoRewindMs(30 * 60_000L) shouldBe 5_000L
            autoRewindMs(3_599_999) shouldBe 5_000L
        }

        test("a break of an hour to a day rewinds fifteen seconds") {
            autoRewindMs(3_600_000) shouldBe 15_000L
            autoRewindMs(12 * 3_600_000L) shouldBe 15_000L
            autoRewindMs(86_399_999) shouldBe 15_000L
        }

        test("a break of a day or more rewinds the full thirty seconds") {
            autoRewindMs(86_400_000) shouldBe 30_000L
            autoRewindMs(30 * 86_400_000L) shouldBe 30_000L
        }

        test("a negative gap rewinds nothing rather than seeking forward") {
            // Clock skew between devices can make lastPlayedAt sit in the future. Rewinding by a
            // negative amount would jump the listener AHEAD of where they stopped — losing content
            // is worse than replaying it, so the ladder floors at zero.
            autoRewindMs(-1) shouldBe 0L
            autoRewindMs(-86_400_000) shouldBe 0L
        }
    })
