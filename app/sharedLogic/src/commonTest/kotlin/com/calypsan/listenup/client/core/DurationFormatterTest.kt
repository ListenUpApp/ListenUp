package com.calypsan.listenup.client.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DurationFormatterTest :
    FunSpec({
        test("hoursMinutes: hours dropped when zero, sub-minute floors to 0m, negatives clamp") {
            DurationFormatter.hoursMinutes(0.milliseconds) shouldBe "0m"
            DurationFormatter.hoursMinutes(30.seconds) shouldBe "0m"
            DurationFormatter.hoursMinutes(59.seconds + 999.milliseconds) shouldBe "0m"
            DurationFormatter.hoursMinutes(1.minutes) shouldBe "1m"
            DurationFormatter.hoursMinutes(45.minutes) shouldBe "45m"
            DurationFormatter.hoursMinutes(59.minutes + 59.seconds) shouldBe "59m"
            DurationFormatter.hoursMinutes(1.hours) shouldBe "1h 0m"
            DurationFormatter.hoursMinutes(1.hours + 1.minutes) shouldBe "1h 1m"
            DurationFormatter.hoursMinutes(2.hours + 5.minutes) shouldBe "2h 5m"
            DurationFormatter.hoursMinutes(25.hours + 5.minutes) shouldBe "25h 5m"
            DurationFormatter.hoursMinutes(-(30.seconds)) shouldBe "0m"
        }

        test("hoursMinutesAlways: hours segment always present, negatives clamp") {
            DurationFormatter.hoursMinutesAlways(0.milliseconds) shouldBe "0h 0m"
            DurationFormatter.hoursMinutesAlways(30.seconds) shouldBe "0h 0m"
            DurationFormatter.hoursMinutesAlways(59.seconds + 999.milliseconds) shouldBe "0h 0m"
            DurationFormatter.hoursMinutesAlways(1.minutes) shouldBe "0h 1m"
            DurationFormatter.hoursMinutesAlways(45.minutes) shouldBe "0h 45m"
            DurationFormatter.hoursMinutesAlways(59.minutes + 59.seconds) shouldBe "0h 59m"
            DurationFormatter.hoursMinutesAlways(1.hours) shouldBe "1h 0m"
            DurationFormatter.hoursMinutesAlways(1.hours + 1.minutes) shouldBe "1h 1m"
            DurationFormatter.hoursMinutesAlways(2.hours + 5.minutes) shouldBe "2h 5m"
            DurationFormatter.hoursMinutesAlways(25.hours + 5.minutes) shouldBe "25h 5m"
            DurationFormatter.hoursMinutesAlways(-(30.seconds)) shouldBe "0h 0m"
        }

        test("hoursMinutesCompact: zero components dropped, negatives clamp") {
            DurationFormatter.hoursMinutesCompact(0.milliseconds) shouldBe "0m"
            DurationFormatter.hoursMinutesCompact(30.seconds) shouldBe "0m"
            DurationFormatter.hoursMinutesCompact(59.seconds + 999.milliseconds) shouldBe "0m"
            DurationFormatter.hoursMinutesCompact(1.minutes) shouldBe "1m"
            DurationFormatter.hoursMinutesCompact(45.minutes) shouldBe "45m"
            DurationFormatter.hoursMinutesCompact(59.minutes + 59.seconds) shouldBe "59m"
            DurationFormatter.hoursMinutesCompact(1.hours) shouldBe "1h"
            DurationFormatter.hoursMinutesCompact(1.hours + 1.minutes) shouldBe "1h 1m"
            DurationFormatter.hoursMinutesCompact(2.hours + 5.minutes) shouldBe "2h 5m"
            DurationFormatter.hoursMinutesCompact(25.hours + 5.minutes) shouldBe "25h 5m"
            DurationFormatter.hoursMinutesCompact(-(30.seconds)) shouldBe "0m"
        }

        test("hoursMinutesOrUnderMinute: sub-minute renders < 1m, negatives clamp") {
            DurationFormatter.hoursMinutesOrUnderMinute(0.milliseconds) shouldBe "< 1m"
            DurationFormatter.hoursMinutesOrUnderMinute(30.seconds) shouldBe "< 1m"
            DurationFormatter.hoursMinutesOrUnderMinute(59.seconds + 999.milliseconds) shouldBe "< 1m"
            DurationFormatter.hoursMinutesOrUnderMinute(1.minutes) shouldBe "1m"
            DurationFormatter.hoursMinutesOrUnderMinute(45.minutes) shouldBe "45m"
            DurationFormatter.hoursMinutesOrUnderMinute(59.minutes + 59.seconds) shouldBe "59m"
            DurationFormatter.hoursMinutesOrUnderMinute(1.hours) shouldBe "1h 0m"
            DurationFormatter.hoursMinutesOrUnderMinute(1.hours + 1.minutes) shouldBe "1h 1m"
            DurationFormatter.hoursMinutesOrUnderMinute(2.hours + 5.minutes) shouldBe "2h 5m"
            DurationFormatter.hoursMinutesOrUnderMinute(25.hours + 5.minutes) shouldBe "25h 5m"
            DurationFormatter.hoursMinutesOrUnderMinute(-(30.seconds)) shouldBe "< 1m"
        }

        test("minutesSecondsClock: MM:SS, minutes never roll into hours, negatives clamp") {
            DurationFormatter.minutesSecondsClock(0.milliseconds) shouldBe "00:00"
            DurationFormatter.minutesSecondsClock(5.seconds) shouldBe "00:05"
            DurationFormatter.minutesSecondsClock(1.minutes + 5.seconds) shouldBe "01:05"
            DurationFormatter.minutesSecondsClock(59.minutes + 59.seconds) shouldBe "59:59"
            DurationFormatter.minutesSecondsClock(1.hours) shouldBe "60:00"
            DurationFormatter.minutesSecondsClock(2.hours + 5.minutes + 5.seconds) shouldBe "125:05"
            DurationFormatter.minutesSecondsClock(-(5.seconds)) shouldBe "00:00"
        }

        test("timeLeft: boundaries are load-bearing") {
            DurationFormatter.timeLeft(0.milliseconds) shouldBe "Almost done"
            DurationFormatter.timeLeft(-(30.seconds)) shouldBe "Almost done"
            DurationFormatter.timeLeft(4.minutes + 59.seconds) shouldBe "Almost done"
            DurationFormatter.timeLeft(5.minutes) shouldBe "5 min left"
            DurationFormatter.timeLeft(59.minutes + 59.seconds) shouldBe "59 min left"
            DurationFormatter.timeLeft(1.hours) shouldBe "1 hr 0 min left"
            DurationFormatter.timeLeft(1.hours + 30.minutes) shouldBe "1 hr 30 min left"
            DurationFormatter.timeLeft(1.hours + 59.minutes) shouldBe "1 hr 59 min left"
            DurationFormatter.timeLeft(2.hours) shouldBe "2h 0m left"
            DurationFormatter.timeLeft(2.hours + 15.minutes) shouldBe "2h 15m left"
        }
    })
