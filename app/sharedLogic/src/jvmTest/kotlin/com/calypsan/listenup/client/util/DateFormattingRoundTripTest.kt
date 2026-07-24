package com.calypsan.listenup.client.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.Locale
import java.util.TimeZone

/**
 * Round-trip guard for [formatDate] against every pattern the app actually renders.
 *
 * The patterns (`"MMMM d, yyyy"`, `"MMMM yyyy"`) are shared verbatim across the platform actuals,
 * so pinning the JVM actual's locale + timezone and asserting a known epoch → known string catches
 * any format-semantics drift introduced while modernizing the Apple actual (which moved from
 * per-call allocation to a cached `NSDateFormatter`). The semantics must not shift.
 */
class DateFormattingRoundTripTest :
    FunSpec({

        lateinit var savedLocale: Locale
        lateinit var savedZone: TimeZone

        beforeTest {
            savedLocale = Locale.getDefault()
            savedZone = TimeZone.getDefault()
            Locale.setDefault(Locale.US)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        }

        afterTest {
            Locale.setDefault(savedLocale)
            TimeZone.setDefault(savedZone)
        }

        // 2026-01-15T12:00:00 UTC
        val epochMillis = 1_768_478_400_000L

        test("MMMM d, yyyy renders the long form") {
            formatDateLong(epochMillis) shouldBe "January 15, 2026"
        }

        test("MMMM yyyy renders month and year") {
            formatDate(epochMillis, "MMMM yyyy") shouldBe "January 2026"
        }
    })
