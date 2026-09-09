package com.calypsan.listenup.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Golden-fixture tests for [SwiftExportGluePatcher], the Kotlin-side twin of
 * [SwiftExportSourcePatcherTest]: a captured slice of the generated `swiftExportMain` glue, the
 * rewritten string, the count.
 */
class SwiftExportGluePatcherTest {
    private val glue =
        checkNotNull(javaClass.getResourceAsStream("/swiftexport/reverse-bridges.kt.txt")) { "missing fixture" }
            .bufferedReader()
            .readText()

    @Test
    fun `a reverse bridge bound to an overloaded method name is dropped with its imported twin`() {
        val outcome = SwiftExportGluePatcher.dropAmbiguousReverseBridges(glue)

        assertEquals(1, outcome.count, "ViewModel.addCloseable is the one overloaded target")
        val out = outcome.content
        assertFalse(out.contains("""BindReverseBridgeToMethod(androidx.lifecycle.ViewModel::class, "addCloseable")"""), "binding gone")
        assertFalse(
            out.contains("addCloseable__TypesOfArguments__anyU20ExportedKotlinPackages_kotlin_AutoCloseable____reverse"),
            "both halves gone",
        )
        // Every forward bridge (the calls Swift makes INTO Kotlin) is untouched; only Swift-overriding is cut.
        assertEquals(
            3,
            Regex("""@ExportedBridge\("androidx_lifecycle_ViewModel_addCloseable""").findAll(out).count(),
            "forward bridges kept",
        )
    }

    @Test
    fun `a reverse bridge whose method name is unique on its class stays`() {
        val out = SwiftExportGluePatcher.dropAmbiguousReverseBridges(glue).content

        assertTrue(out.contains("""BindReverseBridgeToMethod(com.example.Base::class, "greet")"""), "unique name keeps its bridge")
        assertTrue(out.contains("com_example_Base_greet__TypesOfArguments__Swift_String____reverse_swift(self:"), "its imported twin too")
    }

    @Test
    fun `the direct variant of a forward bridge is not counted as an overload`() {
        // `greet` has `__` and `___direct` forward bridges — one overload, two bridges.
        val out = SwiftExportGluePatcher.dropAmbiguousReverseBridges(glue).content
        assertTrue(out.contains("""BindReverseBridgeToMethod(com.example.Base::class, "greet")"""))
    }

    @Test
    fun `the transform is idempotent`() {
        val once = SwiftExportGluePatcher.dropAmbiguousReverseBridges(glue)
        val twice = SwiftExportGluePatcher.dropAmbiguousReverseBridges(once.content)
        assertEquals(once.content, twice.content)
        assertEquals(0, twice.count)
    }
}
