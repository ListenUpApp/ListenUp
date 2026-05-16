package com.calypsan.listenup.client.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
class JobReasonLoggerTest {
    @Test
    fun `shouldLog is false below API 37`() {
        assertFalse(JobReasonLogger.shouldLog(sdkInt = 36))
    }

    @Test
    fun `shouldLog is true on API 37+`() {
        assertTrue(JobReasonLogger.shouldLog(sdkInt = 37))
    }

    @Test
    fun `formatReasonStats renders an empty map as no reasons recorded`() {
        assertEquals("no reasons recorded", JobReasonLogger.formatReasonStats(emptyMap()))
    }

    @Test
    fun `formatReasonStats renders a populated map deterministically`() {
        // JobScheduler.PENDING_JOB_REASON_CONSTRAINT_BATTERY_NOT_LOW = 4.
        // Verified against android-37 android.jar via javap.
        // Note: PENDING_JOB_REASON_BATTERY_SAVER_ENABLED does not exist in API 37;
        // CONSTRAINT_BATTERY_NOT_LOW (=4) is the closest battery-related constant.
        val result = JobReasonLogger.formatReasonStats(mapOf(4 to 5_000L))
        assertEquals("CONSTRAINT_BATTERY_NOT_LOW=5000ms", result)
    }
}
