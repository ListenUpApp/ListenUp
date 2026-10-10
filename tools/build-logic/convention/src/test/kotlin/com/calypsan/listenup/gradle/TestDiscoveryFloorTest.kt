package com.calypsan.listenup.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The discovered-test-count floor decides whether a lane collapsed. It reads the lane's JUnit XML after
 * the lane has run, because a listener inside the test task never hears from a lane that found nothing:
 * with no test class, no suite ever reports, and the old `afterSuite` floor let a zero-test lane pass.
 *
 * These cases pin the count it reads — including the zero a lane with no XML at all must produce — and
 * when it fires. Getting either backwards is silent in the worst direction: a floor that never fires looks
 * exactly like a floor that never needed to.
 */
class TestDiscoveryFloorTest {
    @Test
    fun `a lane that wrote no reports counted zero tests`() {
        assertEquals(0, countReportedTests(emptyList()))
    }

    @Test
    fun `sums the tests attribute of every report`() {
        val reports =
            listOf(
                """<?xml version="1.0"?><testsuite name="A" tests="3" skipped="1" failures="0" errors="0">""",
                """<testsuite name="B" timestamp="t" tests="12" skipped="0"><testcase name="x"/></testsuite>""",
            )
        assertEquals(15, countReportedTests(reports))
    }

    @Test
    fun `a report without a testsuite counts nothing`() {
        assertEquals(0, countReportedTests(listOf("<html>not a report</html>")))
    }

    @Test
    fun `reads the spec class of every testcase`() {
        val reports =
            listOf(
                """<testsuite name="KonsistScopeTest[jvm]" tests="2"><testcase name="a[jvm]" classname="com.x.KonsistScopeTest" time="0"/>""" +
                    """<testcase name="b[jvm]" classname="com.x.KonsistScopeTest" time="0"/></testsuite>""",
                """<testsuite name="Other[jvm]" tests="1"><testcase name="c[jvm]" classname="com.y.Other" time="0"/></testsuite>""",
            )
        assertEquals(setOf("com.x.KonsistScopeTest", "com.y.Other"), reportedSpecClasses(reports))
    }

    @Test
    fun `a missing anchor fails an unfiltered run and names the class`() {
        val message =
            missingAnchorFailure(
                taskLabel = ":app:sharedLogic:jvmTest",
                anchorSpecs = listOf("com.x.KonsistScopeTest", "com.y.Gone"),
                reportedSpecClasses = setOf("com.x.KonsistScopeTest"),
                isFiltered = false,
            )

        assertNotNull(message)
        assertTrue(message.contains("com.y.Gone"), "names the missing anchor: $message")
        assertTrue(!message.contains("com.x.KonsistScopeTest"), "does not name a present anchor: $message")
    }

    @Test
    fun `a present anchor passes`() {
        assertNull(
            missingAnchorFailure(":m:jvmTest", listOf("com.x.A"), setOf("com.x.A", "com.x.B"), isFiltered = false),
        )
    }

    @Test
    fun `a filtered run never fails on anchors`() {
        assertNull(missingAnchorFailure(":m:jvmTest", listOf("com.x.Gone"), emptySet(), isFiltered = true))
    }

    @Test
    fun `an empty anchor list never fails`() {
        assertNull(missingAnchorFailure(":m:jvmTest", emptyList(), emptySet(), isFiltered = false))
    }

    @Test
    fun `fires on an unfiltered lane below the floor`() {
        val message = discoveredCountFailure(taskLabel = ":app:sharedUI:desktopTest", floor = 40, testCount = 3, isFiltered = false)

        assertNotNull(message)
        // The message is the operator's only context, so it must name the lane, what was found,
        // and what was expected — not merely "below the floor".
        assertTrue(message.contains(":app:sharedUI:desktopTest"), "names the task: $message")
        assertTrue(message.contains("only 3 tests"), "names the observed count: $message")
        assertTrue(message.contains("floor of 40"), "names the floor: $message")
    }

    @Test
    fun `fires on a lane that ran nothing`() {
        assertNotNull(discoveredCountFailure(taskLabel = ":contract:jvmTest", floor = 85, testCount = 0, isFiltered = false))
    }

    @Test
    fun `stands down on a filtered run`() {
        assertNull(discoveredCountFailure(taskLabel = ":app:sharedUI:desktopTest", floor = 40, testCount = 3, isFiltered = true))
    }

    @Test
    fun `accepts a count exactly at the floor`() {
        assertNull(discoveredCountFailure(taskLabel = ":app:sharedUI:desktopTest", floor = 40, testCount = 40, isFiltered = false))
    }

    @Test
    fun `accepts a count above the floor`() {
        assertNull(discoveredCountFailure(taskLabel = ":app:sharedUI:desktopTest", floor = 40, testCount = 62, isFiltered = false))
    }
}
