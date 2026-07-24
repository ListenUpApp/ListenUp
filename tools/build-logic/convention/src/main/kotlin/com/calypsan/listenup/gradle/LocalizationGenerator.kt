package com.calypsan.listenup.gradle

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Pure, Gradle-free localization generator.
 *
 * Flattens nested locale JSON (the `strings` source of truth) into the two native string
 * formats the apps consume:
 *  - Android `strings.xml` with snake_case resource names (dotted JSON paths joined by `_`,
 *    sorted alphabetically, XML-escaped).
 *  - A single iOS String Catalog (`.xcstrings`) holding every locale, keyed by the dotted JSON
 *    path, with Android-style `%1$s` format specifiers converted to the iOS `%1$@` form.
 *
 * Output is deterministic so the `verifyStrings` CI gate can detect drift by regenerating and
 * byte-comparing. JSON parsing uses the kotlinx-serialization runtime ([Json.parseToJsonElement])
 * only — no serialization compiler plugin is required in `build-logic`.
 */
object LocalizationGenerator {
    /** Stable, pretty-printed JSON encoder for the iOS String Catalog. */
    private val prettyJson =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    /** Flattens nested locale JSON into a `dotted.path` -> value map. */
    fun parse(json: String): Map<String, String> =
        buildMap { flatten(Json.parseToJsonElement(json).jsonObject, "", this) }

    private fun flatten(
        obj: JsonObject,
        prefix: String,
        out: MutableMap<String, String>,
    ) {
        for ((key, value) in obj) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            when (value) {
                is JsonObject -> flatten(value, path, out)
                else -> out[path] = value.jsonPrimitive.content
            }
        }
    }

    /**
     * Converts Android-style string format specifiers to the iOS equivalents:
     * `%1$s` -> `%1$@`, `%s` -> `%@`. Numeric specifiers (`%1$d`, `%2$f`, …) are left unchanged.
     */
    fun androidToIosFormat(value: String): String {
        val dollar = '$'
        return Regex("%(\\d+)\\${'$'}s")
            .replace(value) { "%${it.groupValues[1]}$dollar@" }
            .replace("%s", "%@")
    }

    /**
     * Escapes a value for inclusion as XML string content.
     *
     * Only the two characters that are genuinely special in XML element content are entity-escaped
     * (`&`, `<`). Apostrophes are emitted RAW: this catalog is consumed by compose-resources' XML
     * parser, not by Android's AAPT. AAPT-style `\'` escaping (which compose-resources does not
     * unescape) would leak a literal backslash into `stringResource()` output (issue #1079); a bare
     * `'` is perfectly valid in XML element content.
     */
    private fun xmlEscape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")

    /** Dotted JSON path -> Android resource name (snake_case, no dots). */
    private fun snakeKey(dotted: String): String = dotted.replace('.', '_')

    /**
     * Renders a flattened locale map as an Android `strings.xml` document: snake_case resource
     * names, sorted alphabetically, values XML-escaped. Format specifiers are preserved verbatim.
     */
    fun androidXml(strings: Map<String, String>): String {
        // Two distinct dotted keys can collapse to the same snake_case resource name
        // (e.g. `a.b_c` and `a_b.c` both -> `a_b_c`). Fail loudly rather than let
        // `toSortedMap()` silently drop one — the generated XML must round-trip every key.
        val collisions = strings.keys.groupBy(::snakeKey).filterValues { it.size > 1 }
        require(collisions.isEmpty()) {
            "Snake-case key collision in Android resources: " +
                collisions.entries.joinToString("; ") { (snake, keys) -> "$snake <- ${keys.sorted()}" }
        }
        return buildString {
            appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
            appendLine("<resources>")
            strings.toSortedMap().forEach { (key, value) ->
                appendLine("""    <string name="${snakeKey(key)}">${xmlEscape(value)}</string>""")
            }
            appendLine("</resources>")
        }
    }

    /**
     * Renders every locale as a single iOS String Catalog (`.xcstrings`).
     *
     * @param localesByCode flattened locale maps keyed by language code (e.g. `"en"`).
     * @param sourceLanguage the catalog's `sourceLanguage` (the development language).
     *
     * Keys are the dotted JSON paths, sorted alphabetically; each holds a `stringUnit` per locale
     * with `state: "translated"` and the format-converted value. The result is built as a
     * [JsonObject] in sorted insertion order and pretty-printed, so it is valid JSON Xcode parses
     * as a String Catalog and is byte-identical across runs for the same input.
     */
    fun xcstrings(
        localesByCode: Map<String, Map<String, String>>,
        sourceLanguage: String,
    ): String {
        val allKeys = localesByCode.values.flatMap { it.keys }.toSortedSet()
        val catalog =
            buildJsonObject {
                put("sourceLanguage", sourceLanguage)
                putJsonObject("strings") {
                    for (key in allKeys) {
                        putJsonObject(key) {
                            putJsonObject("localizations") {
                                val locales =
                                    localesByCode.entries
                                        .filter { it.value.containsKey(key) }
                                        .sortedBy { it.key }
                                for ((code, map) in locales) {
                                    putJsonObject(code) {
                                        putJsonObject("stringUnit") {
                                            put("state", "translated")
                                            put("value", androidToIosFormat(map.getValue(key)))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                put("version", "1.0")
            }
        return prettyJson.encodeToString(JsonObject.serializer(), catalog) + "\n"
    }
}
