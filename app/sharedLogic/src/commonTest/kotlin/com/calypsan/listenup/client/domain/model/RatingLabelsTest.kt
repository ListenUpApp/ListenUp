package com.calypsan.listenup.client.domain.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private const val DAY = 86_400_000L

/** The labels every platform shows beside a rating: your listeners' average and how fresh a score is. */
class RatingLabelsTest :
    FunSpec({
        test("your listeners' average reads to one decimal, like the score") {
            RatingLabels.listenerAverageLabel(ListenerAverage(averageHalfStars = 8.0, count = 3)) shouldBe "4.0"
            RatingLabels.listenerAverageLabel(ListenerAverage(averageHalfStars = 7.6, count = 5)) shouldBe "3.8"
            RatingLabels.listenerAverageLabel(ListenerAverage(averageHalfStars = 9.0, count = 1)) shouldBe "4.5"
        }

        test("days since a fetch count whole days, and a fetch from the device's future is today") {
            RatingLabels.daysSince(fetchedAtMs = 10 * DAY, nowMs = 11 * DAY - 1) shouldBe 0
            RatingLabels.daysSince(fetchedAtMs = 10 * DAY, nowMs = 11 * DAY) shouldBe 1
            RatingLabels.daysSince(fetchedAtMs = 10 * DAY, nowMs = 13 * DAY + 5) shouldBe 3
            RatingLabels.daysSince(fetchedAtMs = 10 * DAY, nowMs = 9 * DAY) shouldBe 0
        }
    })
