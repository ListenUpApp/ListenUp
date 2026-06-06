package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.dto.ContributorRole

/** A contributor parsed from a raw metadata string: a normalized [name] and its [role]. */
data class ParsedContributor(
    val name: String,
    val role: ContributorRole,
)

/**
 * Splits a raw author/narrator metadata string into individual contributors with roles.
 *
 * A synthesis of two references: name-splitting + `Last, First` normalization from
 * Audiobookshelf's `parseNameString.js`, and ` - Role` suffix extraction from the Go
 * scanner's `roleMap`. Splits on the explicit person separators `;` / `&` / ` and `;
 * within a segment a single comma is treated as `Last, First`. When no explicit
 * separator is present, a comma list is disambiguated by Audiobookshelf's rule: if the
 * first comma chunk is a bare surname (no space) the chunks are alternating `Last, First`
 * pairs, otherwise each chunk is a full `First Last` name. An unmapped ` - Role` suffix
 * keeps the person at [defaultRole] (we never drop a valid name).
 */
object ContributorParser {
    private val roleMap: Map<String, ContributorRole> =
        mapOf(
            "author" to ContributorRole.AUTHOR,
            "writer" to ContributorRole.AUTHOR,
            "narrator" to ContributorRole.NARRATOR,
            "reader" to ContributorRole.NARRATOR,
            "read by" to ContributorRole.NARRATOR,
            "translator" to ContributorRole.TRANSLATOR,
            "translated by" to ContributorRole.TRANSLATOR,
            "editor" to ContributorRole.EDITOR,
            "edited by" to ContributorRole.EDITOR,
            "foreword" to ContributorRole.FOREWORD,
            "foreword by" to ContributorRole.FOREWORD,
            "introduction" to ContributorRole.INTRODUCTION,
            "introduction by" to ContributorRole.INTRODUCTION,
            "intro" to ContributorRole.INTRODUCTION,
            "afterword" to ContributorRole.AFTERWORD,
            "afterword by" to ContributorRole.AFTERWORD,
            "producer" to ContributorRole.PRODUCER,
            "adaptation" to ContributorRole.ADAPTER,
            "adapted by" to ContributorRole.ADAPTER,
            "adapter" to ContributorRole.ADAPTER,
            "illustrator" to ContributorRole.ILLUSTRATOR,
            "illustrated by" to ContributorRole.ILLUSTRATOR,
        )

    private val personSeparator = Regex("""\s*&\s*|\s+and\s+|\s*;\s*""")

    // CJK Unified Ideographs + Hiragana + Katakana phonetic extensions — skip name-splitting for these scripts (matches ABS).
    private val cjkRegex = Regex("""[一-鿿぀-ヿㇰ-ㇿ]""")

    fun parse(
        raw: String,
        defaultRole: ContributorRole,
    ): List<ParsedContributor> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()

        val (segments, explicitSplit) = splitPersons(trimmed)
        val result = mutableListOf<ParsedContributor>()
        for (segment in segments) {
            // Peel the role suffix before resolving the name so a "Last, First - Role" entry
            // keeps its role while the name still normalizes correctly.
            val (namePart, role) = peelRole(segment, defaultRole)
            val names =
                if (explicitSplit) {
                    listOfNotNull(normalizeLastFirst(namePart).ifBlank { null })
                } else {
                    splitCommaList(namePart)
                }
            names.forEach { name -> result += ParsedContributor(name, role) }
        }
        return result.distinct()
    }

    /** Splits on every explicit person separator (`&`, ` and `, `;`) in one pass; returns (segments, wasSplit). */
    private fun splitPersons(raw: String): Pair<List<String>, Boolean> =
        if (personSeparator.containsMatchIn(raw)) {
            raw.split(personSeparator).map { it.trim() }.filter { it.isNotEmpty() } to true
        } else {
            listOf(raw) to false
        }

    /** Peels a trailing ` - Role` suffix; an unmapped suffix falls back to [defaultRole]. */
    private fun peelRole(
        entry: String,
        defaultRole: ContributorRole,
    ): Pair<String, ContributorRole> {
        val idx = entry.indexOf(" - ")
        if (idx < 0) return entry.trim() to defaultRole
        val left = entry.substring(0, idx).trim()
        val roleStr = entry.substring(idx + 3).trim().lowercase()
        return left to (roleMap[roleStr] ?: defaultRole)
    }

    /** `"Surname, Given"` (bare surname before the comma) -> `"Given Surname"`; otherwise unchanged. */
    private fun normalizeLastFirst(name: String): String {
        val parts = name.split(",")
        if (parts.size == 2) {
            val last = parts[0].trim()
            val first = parts[1].trim()
            if (last.isNotEmpty() && first.isNotEmpty() && !last.contains(' ')) return "$first $last"
        }
        return name.trim()
    }

    /** Disambiguates a comma list (no explicit separator) into one or more full names. */
    private fun splitCommaList(entry: String): List<String> {
        if (!entry.contains(',')) return listOf(entry.trim())
        if (cjkRegex.containsMatchIn(entry)) return listOf(entry.trim())
        val chunks = entry.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (chunks.isEmpty()) return emptyList()
        return if (!chunks[0].contains(' ')) {
            val usable = if (chunks.size % 2 != 0) chunks.dropLast(1) else chunks
            usable.chunked(2).map { (last, first) -> "$first $last" }
        } else {
            chunks
        }
    }
}
