package com.calypsan.listenup.client.diagnostics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [JobReasonLogger].
 *
 * These tests cover the pure-logic helpers ([JobReasonLogger.shouldLog] and
 * [JobReasonLogger.formatReasonStats]) that can be verified on JVM without an Android device.
 *
 * Note: [JobReasonLogger.logPendingReasonsFor] is an API-37-gated side-effectful function
 * that requires a real [android.app.job.JobScheduler] instance; it is covered by
 * operator log inspection rather than unit tests.
 */
class JobReasonLoggerTest :
    FunSpec({
        test("shouldLog is false below API 37") {
            JobReasonLogger.shouldLog(sdkInt = 36) shouldBe false
        }

        test("shouldLog is true on API 37+") {
            JobReasonLogger.shouldLog(sdkInt = 37) shouldBe true
        }

        test("formatReasonStats renders an empty map as no reasons recorded") {
            JobReasonLogger.formatReasonStats(emptyMap()) shouldBe "no reasons recorded"
        }

        test("formatReasonStats renders a populated map deterministically") {
            // JobScheduler.PENDING_JOB_REASON_CONSTRAINT_BATTERY_NOT_LOW = 4.
            // Verified against android-37 android.jar via javap.
            // Note: PENDING_JOB_REASON_BATTERY_SAVER_ENABLED does not exist in API 37;
            // CONSTRAINT_BATTERY_NOT_LOW (=4) is the closest battery-related constant.
            JobReasonLogger.formatReasonStats(mapOf(4 to 5_000L)) shouldBe "CONSTRAINT_BATTERY_NOT_LOW=5000ms"
        }
    })
