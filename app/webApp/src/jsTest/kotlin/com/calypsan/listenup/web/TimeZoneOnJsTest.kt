package com.calypsan.listenup.web

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * kotlinx-datetime ships no timezone database on js, so zone-aware conversion depends on an
 * npm package rather than the platform. `TimeFormatting` converts on a display path, so a
 * missing database would surface as a broken screen rather than an obvious startup error.
 */
class TimeZoneOnJsTest :
    FunSpec({
        test("an instant converts to local time in the browser") {
            // 2025-07-20T08:26:40Z. Asserting the year rather than merely "not zero": a
            // conversion that silently produced a wrong-but-nonzero date would satisfy the
            // weaker check, which is the vacuous-green trap this lane is prone to. Deliberately
            // not asserting the day — real zone offsets span roughly -12h to +14h, so a
            // mid-month UTC instant lands on the 19th, 20th or 21st depending on the runner's
            // zone, while the year and month are stable everywhere.
            val local =
                Instant
                    .fromEpochMilliseconds(MID_JULY_2025_UTC_MILLIS)
                    .toLocalDateTime(TimeZone.currentSystemDefault())

            local.year shouldBe EXPECTED_YEAR
        }
    })

/** 2025-07-20T08:26:40Z — mid-month, so no real zone offset can push it into another month. */
private const val MID_JULY_2025_UTC_MILLIS = 1_753_000_000_000

private const val EXPECTED_YEAR = 2025
