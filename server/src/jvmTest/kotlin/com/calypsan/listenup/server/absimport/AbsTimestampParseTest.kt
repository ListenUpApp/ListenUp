@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.absimport

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

/**
 * Unit coverage for [parseAbsTimestampMs] across every timestamp form an ABS backup can carry.
 *
 * The regression that motivated this: Sequelize-on-SQLite writes `DataTypes.DATE` columns as
 * `2024-06-12 02:48:10.063 +00:00` — space-separated, with a millisecond fraction and an explicit
 * ` +00:00` offset. The old parser fell through every branch on that form and returned 0L, so every
 * imported listening position got `lastPlayedAt = 0` and Continue Listening lost its ordering.
 */
class AbsTimestampParseTest :
    FunSpec({

        test("parses the real Sequelize-SQLite offset form (the #537 regression)") {
            val expected = Instant.parse("2024-06-12T02:48:10.063Z").toEpochMilliseconds()
            parseAbsTimestampMs("2024-06-12 02:48:10.063 +00:00") shouldBe expected
        }

        test("parses the clean ISO-8601 Z form") {
            parseAbsTimestampMs("2022-01-17T04:33:12.000Z") shouldBe 1_642_393_992_000L
        }

        test("parses the offsetless space-separated SQLite form as UTC") {
            parseAbsTimestampMs("2022-01-16 04:33:12") shouldBe 1_642_307_592_000L
        }

        test("parses a bare numeric epoch-millis value") {
            parseAbsTimestampMs("1642393992000") shouldBe 1_642_393_992_000L
        }

        test("parses a bare numeric epoch-seconds value") {
            parseAbsTimestampMs("1642393992") shouldBe 1_642_393_992_000L
        }

        test("returns 0L for null, blank, and unparseable input") {
            parseAbsTimestampMs(null) shouldBe 0L
            parseAbsTimestampMs("   ") shouldBe 0L
            parseAbsTimestampMs("not a date") shouldBe 0L
        }
    })
