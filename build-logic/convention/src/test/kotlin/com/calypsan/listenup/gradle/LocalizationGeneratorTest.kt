package com.calypsan.listenup.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalizationGeneratorTest {
    private val en =
        """
        {
          "common": { "add_name": "Add %1${'$'}s", "cancel": "Cancel", "amp": "Tom & Jerry" },
          "admin": { "n_day": "1 day" }
        }
        """.trimIndent()

    @Test
    fun `parse flattens nested json to dotted keys`() {
        val parsed = LocalizationGenerator.parse(en)
        assertEquals(
            setOf("common.add_name", "common.cancel", "common.amp", "admin.n_day"),
            parsed.keys,
        )
        assertEquals("Add %1${'$'}s", parsed["common.add_name"])
        assertEquals("Tom & Jerry", parsed["common.amp"])
    }

    @Test
    fun `android xml uses snake keys, sorted, escaped`() {
        val xml = LocalizationGenerator.androidXml(LocalizationGenerator.parse(en))
        // snake keys (dots -> underscores), alphabetical order, & -> &amp;, %1$s preserved verbatim.
        val expected =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="admin_n_day">1 day</string>
                <string name="common_add_name">Add %1${'$'}s</string>
                <string name="common_amp">Tom &amp; Jerry</string>
                <string name="common_cancel">Cancel</string>
            </resources>

            """.trimIndent()
        assertEquals(expected, xml)
    }

    @Test
    fun `android xml escapes apostrophe and angle bracket`() {
        val strings = mapOf("common.tricky" to "It's a <tag>")
        val xml = LocalizationGenerator.androidXml(strings)
        assertTrue(xml.contains("""<string name="common_tricky">It\'s a &lt;tag></string>"""))
    }

    @Test
    fun `xcstrings uses dotted keys and converts format specifiers`() {
        val cat =
            LocalizationGenerator.xcstrings(
                mapOf("en" to LocalizationGenerator.parse(en)),
                sourceLanguage = "en",
            )
        assertTrue(cat.contains("\"common.add_name\""), "expected dotted key")
        assertTrue(cat.contains("Add %1$@"), "expected %1\$s -> %1\$@ conversion")
        assertTrue(cat.contains("\"sourceLanguage\": \"en\""), "expected sourceLanguage")
        assertTrue(cat.contains("\"common.amp\""))
        assertTrue(cat.contains("Tom & Jerry"), "JSON string value, no XML escaping of &")
        assertTrue(cat.contains("\"state\": \"translated\""))
    }

    @Test
    fun `xcstrings is valid json the catalog shape Xcode expects`() {
        val cat =
            LocalizationGenerator.xcstrings(
                mapOf("en" to LocalizationGenerator.parse(en)),
                sourceLanguage = "en",
            )
        // Parseable JSON with the canonical String Catalog top-level keys.
        val element =
            kotlinx.serialization.json.Json
                .parseToJsonElement(cat)
        val obj = element as kotlinx.serialization.json.JsonObject
        assertTrue(obj.containsKey("sourceLanguage"))
        assertTrue(obj.containsKey("strings"))
        assertTrue(obj.containsKey("version"))
    }

    @Test
    fun `format conversion preserves numeric specifiers`() {
        assertEquals("%1${'$'}@ has %2${'$'}d", LocalizationGenerator.androidToIosFormat("%1${'$'}s has %2${'$'}d"))
        assertEquals("%@", LocalizationGenerator.androidToIosFormat("%s"))
    }

    @Test
    fun `generation is deterministic`() {
        val a = LocalizationGenerator.xcstrings(mapOf("en" to LocalizationGenerator.parse(en)), "en")
        val b = LocalizationGenerator.xcstrings(mapOf("en" to LocalizationGenerator.parse(en)), "en")
        assertEquals(a, b)
    }
}
