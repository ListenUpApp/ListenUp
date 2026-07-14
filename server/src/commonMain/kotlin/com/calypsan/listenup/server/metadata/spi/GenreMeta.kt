package com.calypsan.listenup.server.metadata.spi

/**
 * Whether a genre-family term is a formal genre or a free-form tag.
 *
 * Catalogs mix curated shelf categories with loose keyword tags; keeping the
 * distinction lets a later step route [GENRE][GenreKind.GENRE] into the genre
 * taxonomy and [TAG][GenreKind.TAG] into free-form book tags.
 */
enum class GenreKind {
    /** A formal, curated genre category. */
    GENRE,

    /** A free-form keyword tag. */
    TAG,
}

/**
 * One genre-family term from a [GenreSource] — a genre or a tag, distinguished by
 * [kind]. [name] is the catalog's label verbatim.
 */
data class GenreMeta(
    /** The term's label, as the catalog spells it. */
    val name: String,
    /** Whether this is a formal genre or a free-form tag. */
    val kind: GenreKind,
)
