package com.calypsan.listenup.gradle

/**
 * Static extraction of the localization keys iOS Swift actually asks for, so they can be checked
 * against `en.json` — the source of truth every localization artifact is generated from.
 *
 * **The gap this closes.** `verifyStrings` regenerates the artifacts from `en.json` and diffs them
 * against what's committed. That catches *drift* in the generated files, but it has no idea which
 * keys Swift requests — so `String(localized: "admin.add_this_folder")`, a key present in no
 * catalog at all, sailed through CI and rendered the raw key text to admins. Nothing caught it
 * locally either, because Xcode scrapes Swift for `String(localized:)` keys and *writes them back*
 * into the generated `.xcstrings` as empty entries, silently inventing the missing key on the
 * developer's machine (see the repo's history of stashed `.xcstrings` churn).
 *
 * iOS resolves a missing key by rendering the key itself, so this failure is always user-visible
 * and never throws — exactly the kind of defect a build gate has to catch.
 */
object SwiftStringKeys {
    /** `namespace.key` — lowercase, underscore-separated, exactly the shape `LocalizationGenerator` emits. */
    private val keyShape = Regex("""^[a-z][a-z0-9_]*(?:\.[a-z0-9_]+)+$""")

    private const val CALL_PREFIX = "String(localized:"

    private val stringLiteral = Regex(""""([^"\\]*)"""")

    /**
     * Every statically-resolvable localization key referenced by [source].
     *
     * Scans the whole argument span of each `String(localized: …)` call rather than just a leading
     * literal, so a ternary (`isReread ? "a.b" : "c.d"`) yields *both* keys. That shape is not
     * hypothetical: it ships in `ActivityFeedObserver`, and matching only a leading literal is
     * precisely the blind spot that let `NoHardcodedUiStringRule` miss the player's hardcoded
     * strings.
     *
     * Calls whose argument carries no literal (`String(localized: metric.titleKey)`,
     * `String(localized: String.LocalizationValue(key))`) are skipped — the key is only known at
     * runtime, so there is nothing to verify and guessing would false-positive.
     */
    fun referencedKeys(source: String): Set<String> =
        buildSet {
            val text = stripComments(source)
            var index = text.indexOf(CALL_PREFIX)
            while (index >= 0) {
                // The `(` of `String(` — the span to scan runs from there to its matching `)`.
                val open = index + "String".length
                val close = matchingParen(text, open)
                if (close > open) {
                    stringLiteral
                        .findAll(text.substring(open + 1, close))
                        .map { it.groupValues[1] }
                        .filterTo(this) { keyShape.matches(it) }
                }
                index = text.indexOf(CALL_PREFIX, index + CALL_PREFIX.length)
            }
        }

    /**
     * Keys referenced by [swiftSources] that are absent from [knownKeys], mapped to the files
     * referencing them (sorted, so the failure message is stable).
     */
    fun missingKeys(
        swiftSources: Map<String, String>,
        knownKeys: Set<String>,
    ): Map<String, List<String>> =
        buildMap<String, MutableList<String>> {
            for ((path, source) in swiftSources.entries.sortedBy { it.key }) {
                for (key in referencedKeys(source).sorted()) {
                    if (key !in knownKeys) getOrPut(key) { mutableListOf() } += path
                }
            }
        }

    /** Index of the `)` matching the `(` at [open], or -1 when unbalanced. */
    private fun matchingParen(
        text: String,
        open: Int,
    ): Int {
        var depth = 0
        var inString = false
        var i = open
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '\\' && inString -> {
                    i++
                }

                ch == '"' -> {
                    inString = !inString
                }

                inString -> {
                    Unit
                }

                ch == '(' -> {
                    depth++
                }

                ch == ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun stripComments(source: String): String =
        source
            .replace(Regex("""//[^\n]*"""), "")
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
}
