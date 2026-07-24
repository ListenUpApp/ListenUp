package com.calypsan.listenup.gradle

import kotlin.test.Test
import kotlin.test.assertEquals

class SwiftStringKeysTest {
    @Test
    fun `extracts a plain literal key`() {
        assertEquals(
            setOf("admin.save_and_scan_folder"),
            SwiftStringKeys.referencedKeys("""Label(String(localized: "admin.save_and_scan_folder"), systemImage: "plus")"""),
        )
    }

    @Test
    fun `extracts every key from a ternary argument`() {
        // The shape that blinds a naive "literal immediately after the label" matcher — and
        // exactly how NoHardcodedUiStringRule missed the player's hardcoded strings.
        assertEquals(
            setOf("discover.activity_reread", "discover.activity_started"),
            SwiftStringKeys.referencedKeys(
                """String(localized: model.isReread ? "discover.activity_reread" : "discover.activity_started")""",
            ),
        )
    }

    @Test
    fun `extracts multiple calls from one source`() {
        assertEquals(
            setOf("common.edit", "common.delete"),
            SwiftStringKeys.referencedKeys(
                """
                Button(String(localized: "common.edit")) {}
                Button(String(localized: "common.delete")) {}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `ignores a dynamic key it cannot resolve statically`() {
        // `String(localized: String.LocalizationValue(key))` and `String(localized: metric.titleKey)`
        // carry no literal — nothing to verify, and guessing would false-positive.
        assertEquals(
            emptySet(),
            SwiftStringKeys.referencedKeys(
                """
                String(format: String(localized: String.LocalizationValue(key)), count)
                Text(String(localized: metric.titleKey))
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `ignores non-key literals`() {
        // Only namespace-dotted literals are keys; a format string or plain word is not.
        assertEquals(
            emptySet(),
            SwiftStringKeys.referencedKeys("""String(localized: "%.1f×")"""),
        )
    }

    @Test
    fun `ignores a localized call inside a line comment`() {
        assertEquals(
            emptySet(),
            SwiftStringKeys.referencedKeys("""// String(localized: "admin.some_removed_key")"""),
        )
    }

    @Test
    fun `missingKeys reports only keys absent from the catalog`() {
        val swift =
            """
            Label(String(localized: "admin.add_this_folder"))
            Label(String(localized: "admin.save_and_scan_folder"))
            """.trimIndent()
        assertEquals(
            setOf("admin.add_this_folder"),
            SwiftStringKeys
                .missingKeys(
                    swiftSources = mapOf("LibrarySettingsView.swift" to swift),
                    knownKeys = setOf("admin.save_and_scan_folder"),
                ).keys,
        )
    }

    @Test
    fun `missingKeys attributes each key to the files referencing it`() {
        assertEquals(
            listOf("A.swift", "B.swift"),
            SwiftStringKeys.missingKeys(
                swiftSources =
                    mapOf(
                        "A.swift" to """String(localized: "nope.gone")""",
                        "B.swift" to """String(localized: "nope.gone")""",
                    ),
                knownKeys = emptySet(),
            )["nope.gone"],
        )
    }
}
