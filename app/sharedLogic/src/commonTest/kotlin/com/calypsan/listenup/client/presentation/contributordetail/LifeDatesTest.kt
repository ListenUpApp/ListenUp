package com.calypsan.listenup.client.presentation.contributordetail

import com.calypsan.listenup.client.util.formatDateLong
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.time.Duration.Companion.hours

/**
 * [formatLifeDates] — the line under a contributor's name that Android and web both draw. The month
 * and day wording comes from the platform's own long-date formatter, so these specs compare against
 * that formatter rather than pinning one locale's spelling.
 */
class LifeDatesTest :
    FunSpec({
        // The calendar day itself, as the formatter shows it in this process's zone.
        fun shown(iso: String): String {
            val midday = LocalDate.parse(iso).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds() + 12.hours.inWholeMilliseconds
            return formatDateLong(midday)
        }

        test("both dates read as a span") {
            formatLifeDates("1947-09-21", "2024-01-15") shouldBe "${shown("1947-09-21")} – ${shown("2024-01-15")}"
        }

        test("only a birth reads as Born, only a death as Died") {
            formatLifeDates("1947-09-21", null) shouldBe "Born ${shown("1947-09-21")}"
            formatLifeDates(null, "2024-01-15") shouldBe "Died ${shown("2024-01-15")}"
        }

        test("no dates, or dates that are not ISO days, give no line") {
            formatLifeDates(null, null) shouldBe null
            formatLifeDates("1947", "not a date") shouldBe null
            formatLifeDates("1947-13-01", null) shouldBe null
        }

        test("the day shown is the day stored, in any zone west or east of UTC") {
            // Midnight UTC formatted in a zone behind UTC is the evening before.
            formatLifeDates("1947-09-21", null).orEmpty() shouldContain "21"
        }
    })
