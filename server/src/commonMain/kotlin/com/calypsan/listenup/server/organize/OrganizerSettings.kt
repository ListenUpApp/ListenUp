package com.calypsan.listenup.server.organize

/**
 * The organizer's folder-structure schema: curated presets, not a free template language (spec:
 * `2026-07-09-file-organization-design.md` §3). Every preset composes segments that already
 * passed through [PathSanitizer].
 */
enum class StructurePreset {
    /** `Author/Title` — no series folder, ever. */
    AUTHOR_TITLE,

    /** `Author/Series/Title` when the book belongs to a series, else `Author/Title`. */
    AUTHOR_SERIES_TITLE,

    /** `Title` — a single flat segment, no author or series folder. */
    FLAT_TITLE,
}

/** How a book's series-index number is prefixed onto its title segment, when a series folder is present. */
enum class SeriesPrefixStyle {
    /** `Book 1 - Title`. */
    BOOK_N_DASH,

    /** `1 - Title`. */
    N_DASH,

    /** `[1] Title`. */
    BRACKET_N,

    /** No prefix — just `Title`. */
    NONE,
}

/** How the primary author's display name is rendered into a folder segment. */
enum class AuthorForm {
    /** `Brandon Sanderson`. */
    FIRST_LAST,

    /** `Sanderson, Brandon`. */
    LAST_FIRST,
}

/**
 * The admin's chosen organizer schema — persisted in `server_settings`.
 *
 * There is deliberately no `enabled` flag. A folder structure is not an optional feature: an
 * uploaded book has to land *somewhere*, so the rules always exist and merely need a default.
 * "Off" only ever meant "don't rearrange what's already there", which is expressed far better by
 * making the reorganize sweep an explicit, previewed action than by a flag gating everything.
 *
 * What the rules apply to is governed by origin, not by a toggle — *we organize what we write, we
 * don't relocate what you placed*: uploads conform (we choose the path), scan-discovered books are
 * left exactly where they are, and a metadata edit relocates a book only when it was already
 * sitting at its canonical path (see [OrganizeOnEditRelocator]).
 */
data class OrganizerSettings(
    val preset: StructurePreset = StructurePreset.AUTHOR_SERIES_TITLE,
    val seriesPrefix: SeriesPrefixStyle = SeriesPrefixStyle.BOOK_N_DASH,
    val authorForm: AuthorForm = AuthorForm.FIRST_LAST,
)

/**
 * The subset of a book's metadata [OrganizerPathPlanner] needs to derive its canonical path.
 * [isMultiFile] is carried through for downstream consumers (e.g. [OrganizePlanBuilder]'s
 * whole-folder-vs-single-file move shape) — the planner itself derives the same folder structure
 * either way.
 *
 * [seriesSequence] is the stored number, not a label: the planner renders it into a folder segment
 * itself, so the whole-number spelling (`1`, never `1.0`) is decided in exactly one place.
 */
data class BookOrganizeFacts(
    val title: String,
    val subtitle: String?,
    val primaryAuthor: String?,
    val seriesName: String?,
    val seriesSequence: Double?,
    val isMultiFile: Boolean,
)
