package com.calypsan.listenup.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `android xml leaves apostrophes raw and entity-escapes angle brackets`() {
        // #1079: apostrophes must NOT be AAPT-escaped (`\'`) — compose-resources parses this
        // catalog as XML and does not unescape that form, leaking a literal backslash to the UI.
        val strings = mapOf("common.tricky" to "It's a <tag>")
        val xml = LocalizationGenerator.androidXml(strings)
        assertTrue(xml.contains("""<string name="common_tricky">It's a &lt;tag></string>"""))
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
        assertTrue(cat.contains(""""sourceLanguage": "en""""), "expected sourceLanguage")
        assertTrue(cat.contains("\"common.amp\""))
        assertTrue(cat.contains("Tom & Jerry"), "JSON string value, no XML escaping of &")
        assertTrue(cat.contains(""""state": "translated""""))
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
    fun `a literal percent sign is doubled for iOS so String(format) keeps it`() {
        // Compose Resources renders a lone `%` literally and never unescapes `%%`, so the catalog
        // writes "50%" as `%1$d%`. iOS `String(format:)` swallows that trailing `%` ("50 complete").
        assertEquals("%1${'$'}d%% complete", LocalizationGenerator.androidToIosFormat("%1${'$'}d% complete"))
        assertEquals("%1${'$'}@%%", LocalizationGenerator.androidToIosFormat("%1${'$'}s%"))
        assertEquals(
            "%1${'$'}@ · %2${'$'}@ · %3${'$'}@ · %4${'$'}@%%",
            LocalizationGenerator.androidToIosFormat("%1${'$'}s · %2${'$'}s · %3${'$'}s · %4${'$'}s%"),
        )
        assertEquals("%1${'$'}d%% · %2${'$'}@", LocalizationGenerator.androidToIosFormat("%1${'$'}d% · %2${'$'}s"))
        assertEquals("%d of %d", LocalizationGenerator.androidToIosFormat("%d of %d"))
        assertEquals("%1${'$'}@s", LocalizationGenerator.androidToIosFormat("%1${'$'}ss"))
        assertEquals("100%%", LocalizationGenerator.androidToIosFormat("100%%"))
        assertEquals("%.1f", LocalizationGenerator.androidToIosFormat("%.1f"))
    }

    @Test
    fun `generation is deterministic`() {
        val a = LocalizationGenerator.xcstrings(mapOf("en" to LocalizationGenerator.parse(en)), "en")
        val b = LocalizationGenerator.xcstrings(mapOf("en" to LocalizationGenerator.parse(en)), "en")
        assertEquals(a, b)
    }

    @Test
    fun `androidXml throws on snake-case key collision rather than dropping a string`() {
        // `a.b_c` and `a_b.c` both flatten to the Android resource name `a_b_c`.
        val colliding = mapOf("a.b_c" to "first", "a_b.c" to "second")
        val ex = assertFailsWith<IllegalArgumentException> { LocalizationGenerator.androidXml(colliding) }
        assertTrue(ex.message!!.contains("a_b_c"))
    }

    // Compose Resources substitutes only positional specifiers, so an unpositioned `%d` reaches an
    // Android screen verbatim ("%d of %d" did, on the series cards). The generator refuses one.
    @Test
    fun `an unpositioned specifier fails the build, naming the key and the specifier`() {
        for (specifier in listOf("%d", "%s", "%f", "%.1f", "%3d")) {
            val ex =
                assertFailsWith<IllegalArgumentException>(specifier) {
                    LocalizationGenerator.androidXml(mapOf("series.x_of_y" to "$specifier of things"))
                }
            assertTrue(ex.message!!.contains("series.x_of_y"), ex.message)
            assertTrue(ex.message!!.contains(specifier), ex.message)
        }
    }

    @Test
    fun `every offending key is named, not just the first`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                LocalizationGenerator.androidXml(mapOf("a.one" to "%d one", "b.two" to "two %s"))
            }
        assertTrue(ex.message!!.contains("a.one") && ex.message!!.contains("b.two"), ex.message)
    }

    @Test
    fun `positional specifiers, an escaped percent and a literal percent all pass`() {
        val xml =
            LocalizationGenerator.androidXml(
                mapOf(
                    "a.positional" to "%1${'$'}d of %2${'$'}d, %1${'$'}s, %1${'$'}.1f",
                    "a.escaped" to "100%%",
                    "a.literal" to "%1${'$'}d% complete",
                    "a.lone" to "50 % off",
                ),
            )
        assertTrue(xml.contains("""<string name="a_literal">%1${'$'}d% complete</string>"""))
    }
}
