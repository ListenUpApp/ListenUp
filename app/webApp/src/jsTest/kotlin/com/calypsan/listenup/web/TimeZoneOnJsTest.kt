package com.calypsan.listenup.web

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldNotBe
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
            val local =
                Instant
                    .fromEpochMilliseconds(1_753_000_000_000)
                    .toLocalDateTime(TimeZone.currentSystemDefault())

            local.year shouldNotBe 0
        }
    })
