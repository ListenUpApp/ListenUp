package com.calypsan.listenup.gradle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** The pure half of `verifyStrings`: rendered artifacts vs. a baseline lookup. */
class LocalizationArtifactsDriftTest {
    private val android = File("values/strings.xml")
    private val catalog = File("Localizable.xcstrings")
    private val rendered = mapOf(android to "<resources/>", catalog to "{}")

    @Test
    fun `no drift when every baseline matches the rendered content`() {
        assertEquals(emptyList(), LocalizationArtifacts.drift(rendered) { rendered[it] })
    }

    @Test
    fun `drift when a baseline differs`() {
        assertEquals(
            listOf(android),
            LocalizationArtifacts.drift(rendered) { if (it == android) "stale" else rendered[it] },
        )
    }

    @Test
    fun `an artifact absent from the baseline is drift, not a crash`() {
        // A never-committed artifact: report it with the actionable message, don't blow up.
        assertEquals(
            listOf(android),
            LocalizationArtifacts.drift(rendered) { if (it == android) null else rendered[it] },
        )
    }
}
