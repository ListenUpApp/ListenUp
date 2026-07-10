package com.calypsan.listenup.server.organize

/** Constant, English, disk-facing placeholder for a book with no resolved primary author (see KDoc on [formatAuthor]). */
private const val UNKNOWN_AUTHOR = "Unknown Author"

/**
 * Pure `(book metadata, schema settings) → canonical relative path` derivation — the single
 * component that owns organizer path derivation (spec: §3, "One component owns path derivation").
 * Zero I/O: every call is a deterministic function of its arguments, so both the full-library
 * plan builder and the single-book upload/edit seam ([planFor] with a fallback default when the
 * feature is disabled) call through the same logic.
 *
 * Every composed segment passes through [PathSanitizer.sanitize] before joining with `/`, so the
 * result is always a safe, cross-platform relative path.
 */
object OrganizerPathPlanner {
    /** Derives [facts]'s canonical path, relative to its library folder root, under [settings]. */
    fun planFor(
        facts: BookOrganizeFacts,
        settings: OrganizerSettings,
    ): String {
        val sanitizedTitle = PathSanitizer.sanitize(facts.title)
        return when (settings.preset) {
            StructurePreset.FLAT_TITLE -> sanitizedTitle
            StructurePreset.AUTHOR_TITLE -> "${authorSegment(facts, settings)}/$sanitizedTitle"
            StructurePreset.AUTHOR_SERIES_TITLE -> authorSeriesTitlePath(facts, settings, sanitizedTitle)
        }
    }

    private fun authorSeriesTitlePath(
        facts: BookOrganizeFacts,
        settings: OrganizerSettings,
        sanitizedTitle: String,
    ): String {
        val author = authorSegment(facts, settings)
        val seriesName = facts.seriesName?.takeIf { it.isNotBlank() } ?: return "$author/$sanitizedTitle"
        val series = PathSanitizer.sanitize(seriesName)
        val titleSegment =
            PathSanitizer.sanitize(seriesPrefixFor(facts.seriesSequence, settings.seriesPrefix) + facts.title)
        return "$author/$series/$titleSegment"
    }

    private fun authorSegment(
        facts: BookOrganizeFacts,
        settings: OrganizerSettings,
    ): String = PathSanitizer.sanitize(formatAuthor(facts.primaryAuthor, settings.authorForm))

    /** Renders [primaryAuthor] per [form], falling back to the constant [UNKNOWN_AUTHOR] when there's no author to render. */
    private fun formatAuthor(
        primaryAuthor: String?,
        form: AuthorForm,
    ): String {
        val name = primaryAuthor?.trim()
        if (name.isNullOrEmpty()) return UNKNOWN_AUTHOR
        return when (form) {
            AuthorForm.FIRST_LAST -> name
            AuthorForm.LAST_FIRST -> toLastFirst(name)
        }
    }

    /** `"Brandon Sanderson"` → `"Sanderson, Brandon"`; a single-word name is left unchanged (no comma to add). */
    private fun toLastFirst(name: String): String {
        val words = name.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2) return name
        val last = words.last()
        val first = words.dropLast(1).joinToString(" ")
        return "$last, $first"
    }

    /** The literal prefix prepended to a title segment when the book belongs to a series folder. Empty when [sequence] is absent. */
    private fun seriesPrefixFor(
        sequence: String?,
        style: SeriesPrefixStyle,
    ): String {
        val n = sequence?.takeIf { it.isNotBlank() } ?: return ""
        return when (style) {
            SeriesPrefixStyle.BOOK_N_DASH -> "Book $n - "
            SeriesPrefixStyle.N_DASH -> "$n - "
            SeriesPrefixStyle.BRACKET_N -> "[$n] "
            SeriesPrefixStyle.NONE -> ""
        }
    }
}
