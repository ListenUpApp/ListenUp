package com.calypsan.listenup.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SealedHierarchyScannerTest {
    @Test
    fun `nested subtypes are attributed to their sealed parent`() {
        val source =
            """
            sealed interface PlaybackUpdate {
                data class Position(
                    val positionMs: Long,
                    val speed: Float,
                ) : PlaybackUpdate

                data object Restart : PlaybackUpdate
            }
            """.trimIndent()

        assertEquals(mapOf("PlaybackUpdate" to setOf("Position", "Restart")), SealedHierarchyScanner.scan(listOf(source)))
    }

    @Test
    fun `a constructor parameter type is not mistaken for a supertype`() {
        // The regression this scanner was written around: splitting on the FIRST ':' lands inside
        // the constructor parameter list, so `val timer: SleepTimerState` reads as "extends
        // SleepTimerState". That mis-attributed a spurious subtype to 23 parents.
        val source =
            """
            sealed interface SleepTimerState {
                data object Off : SleepTimerState
            }

            data class NowPlayingScreenState(
                val timer: SleepTimerState,
                val title: String,
            )
            """.trimIndent()

        assertEquals(setOf("Off"), SealedHierarchyScanner.scan(listOf(source))["SleepTimerState"])
    }

    @Test
    fun `a generic parameter list before the supertype list is skipped`() {
        val source =
            """
            sealed interface Outcome {
                data class Wrapped<T>(val value: T) : Outcome
            }
            """.trimIndent()

        assertEquals(setOf("Wrapped"), SealedHierarchyScanner.scan(listOf(source))["Outcome"])
    }

    @Test
    fun `an intermediate sealed subtype is excluded`() {
        // Swift Export emits a sealed interface as a protocol, never a `public final class`, so it
        // is never harvested — counting it would make the source expectation unreachable.
        val source =
            """
            sealed interface AppError

            sealed interface AuthError : AppError {
                data object Expired : AuthError
            }
            """.trimIndent()

        val scanned = SealedHierarchyScanner.scan(listOf(source))
        assertEquals(null, scanned["AppError"], "the intermediate sealed interface is not a counted subtype")
        assertEquals(setOf("Expired"), scanned["AuthError"])
    }

    @Test
    fun `an internal subtype is excluded`() {
        val source =
            """
            sealed interface Step {
                data object Shown : Step
                internal data object Hidden : Step
            }
            """.trimIndent()

        assertEquals(setOf("Shown"), SealedHierarchyScanner.scan(listOf(source))["Step"])
    }

    @Test
    fun `a commented-out subtype is not counted`() {
        val source =
            """
            sealed interface Step {
                data object Shown : Step
                // data object Removed : Step
                /* data object AlsoRemoved : Step */
            }
            """.trimIndent()

        assertEquals(setOf("Shown"), SealedHierarchyScanner.scan(listOf(source))["Step"])
    }

    @Test
    fun `subtypes declared in another file still attribute to their parent`() {
        val parent = "sealed interface Reachability"
        val child = "data object Online : Reachability"

        assertEquals(setOf("Online"), SealedHierarchyScanner.scan(listOf(parent, child))["Reachability"])
    }

    @Test
    fun `a file with no sealed types yields nothing`() {
        assertTrue(SealedHierarchyScanner.scan(listOf("data class Plain(val x: Int)")).isEmpty())
    }
}
