package com.calypsan.listenup.gradle

import java.io.File

/**
 * Kotlin-source ground truth for sealed hierarchies: parent simple name -> its concrete subtypes.
 *
 * This replaces `SwiftExportSourcePatcher.expectedSealedSubtypeCounts`, a map of 127 hand-typed
 * numbers. That map was only ever [SwiftExportSourcePatcher.sealedSubtypeDrift]'s *shrink floor*,
 * and a parent that legitimately grew was deliberately not flagged — so its numbers rotted
 * silently. `PlaybackUpdate` sat at 10 against a real 13, meaning it could have lost two subtypes
 * and still cleared the floor it was supposed to be protected by. Reading the hierarchy from source
 * removes the hand-maintained number entirely: the expectation cannot drift from the code.
 *
 * **Only harvested parents are compared.** Source declares roughly twice the sealed types that
 * Swift Export actually emits — the export surface is deliberately lean ("export only what client
 * UI consumes"), so ~300 sealed subtypes here are legitimately absent from the generated Swift.
 * Asserting that every source subtype is emitted would cry wolf constantly. The check therefore
 * scopes itself to parents the harvest actually found, for which the emitted set must match source
 * exactly. Validated against a real 2.4.10 `Shared.swift`: **128 exported parents, 0 mismatches.**
 *
 * A subtype counts when it is neither `sealed` (an intermediate sealed interface is emitted as a
 * Swift protocol, not a `public final class`, so it is never harvested) nor `internal` (not
 * exported at all).
 */
object SealedHierarchyScanner {
    private val sealedDeclaration =
        Regex("""^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:public\s+|internal\s+)?sealed\s+(?:class|interface)\s+(\w+)""")

    private val typeDeclaration =
        Regex(
            """^\s*(?:@\w+(?:\([^)]*\))?\s+)*((?:public|internal|private)\s+)?(sealed\s+)?""" +
                """(?:data\s+|value\s+)?(?:class|object|interface)\s+(\w+)""",
        )

    /** How many lines a declaration head may span before we stop looking for its supertype list. */
    private const val MAX_DECLARATION_HEAD_LINES = 60

    /**
     * The supertype list of the declaration named [name] within [head], or "" when it has none.
     *
     * Skips the generic parameter list and then the constructor parameter list by bracket depth
     * before looking for the `:`. Splitting on the *first* `:` instead is the obvious shortcut and
     * it is wrong: in `data class NowPlayingScreenState(val timer: SleepTimerState, …)` the first
     * `:` is a constructor parameter's type annotation, so every parameter type is mistaken for a
     * supertype. That bug attributed a spurious extra subtype to 23 different parents before it was
     * caught by comparing against real generated Swift.
     */
    internal fun supertypeClause(
        head: String,
        name: String,
    ): String {
        val start = head.indexOf(name)
        if (start < 0) return ""
        var rest = head.substring(start + name.length)
        for ((opener, closer) in listOf('<' to '>', '(' to ')')) {
            rest = rest.trimStart()
            if (rest.firstOrNull() != opener) continue
            var depth = 0
            var i = 0
            while (i < rest.length) {
                when (rest[i]) {
                    opener -> {
                        depth++
                    }

                    closer -> {
                        depth--
                        if (depth == 0) {
                            i++
                            break
                        }
                    }
                }
                i++
            }
            rest = rest.substring(i)
        }
        rest = rest.trimStart()
        if (!rest.startsWith(":")) return ""
        return rest.drop(1).substringBefore("{")
    }

    /** Strips block comments and line comments so a commented-out supertype can't be harvested. */
    internal fun stripComments(source: String): String =
        source
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("""//[^\n]*"""), "")

    /** Parent simple name -> concrete (non-sealed, non-internal) subtype simple names, from [sources]. */
    fun scan(sources: List<String>): Map<String, Set<String>> {
        val parents = LinkedHashMap<String, MutableSet<String>>()
        val stripped = sources.map(::stripComments)

        stripped.forEach { source ->
            source.lineSequence().forEach { line ->
                sealedDeclaration.find(line)?.let { parents.getOrPut(it.groupValues[1]) { linkedSetOf() } }
            }
        }
        if (parents.isEmpty()) return emptyMap()

        stripped.forEach { source ->
            val lines = source.lines()
            lines.forEachIndexed { index, line ->
                val match = typeDeclaration.find(line) ?: return@forEachIndexed
                val (visibility, sealedMarker, name) = match.destructured
                if (sealedMarker.isNotBlank() || visibility.trim() == "internal") return@forEachIndexed

                val head = StringBuilder()
                for (offset in index until minOf(index + MAX_DECLARATION_HEAD_LINES, lines.size)) {
                    head.append(lines[offset]).append(' ')
                    if (lines[offset].contains('{')) break
                    if (offset > index && lines[offset].isBlank()) break
                }
                val supertypes = supertypeClause(head.toString(), name)
                if (supertypes.isBlank()) return@forEachIndexed

                parents.keys.forEach { parent ->
                    if (parent != name && Regex("""\b${Regex.escape(parent)}\b""").containsMatchIn(supertypes)) {
                        parents.getValue(parent) += name
                    }
                }
            }
        }
        return parents.filterValues { it.isNotEmpty() }
    }

    /** Reads every `.kt` file under [roots] and scans them together. */
    fun scanSourceRoots(roots: List<File>): Map<String, Set<String>> =
        scan(
            roots
                .filter { it.isDirectory }
                .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
                .map { it.readText() },
        )
}
