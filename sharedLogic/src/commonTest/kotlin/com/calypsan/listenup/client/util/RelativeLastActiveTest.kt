package com.calypsan.listenup.client.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RelativeLastActiveTest :
    FunSpec({
        val now = 1_700_000_000_000L

        fun ago(ms: Long) = now - ms
        val minute = 60_000L
        val hour = 60 * minute
        val day = 24 * hour

        test("under a minute is Just now") {
            relativeLastActive(ago(30_000L), now) shouldBe "Just now"
        }
        test("minutes are pluralized") {
            relativeLastActive(ago(1 * minute), now) shouldBe "1 minute ago"
            relativeLastActive(ago(5 * minute), now) shouldBe "5 minutes ago"
        }
        test("hours are pluralized") {
            relativeLastActive(ago(1 * hour), now) shouldBe "1 hour ago"
            relativeLastActive(ago(2 * hour), now) shouldBe "2 hours ago"
        }
        test("one-to-two days is Yesterday") {
            relativeLastActive(ago(25 * hour), now) shouldBe "Yesterday"
        }
        test("days then weeks") {
            relativeLastActive(ago(3 * day), now) shouldBe "3 days ago"
            relativeLastActive(ago(14 * day), now) shouldBe "2 weeks ago"
        }
        test("future or clock-skew clamps to Just now") {
            relativeLastActive(now + 10_000L, now) shouldBe "Just now"
        }
    })
