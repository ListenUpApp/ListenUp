package com.calypsan.listenup.server.metadata.spi

/**
 * A character appearing in a book, as a provider reports it.
 *
 * The honest empty slot: no public metadata source exposes per-book character
 * lists today, so [CharacterSource] has no real implementation and this type has
 * no populated call site yet. It is defined so the capability vocabulary is
 * complete and a future community/manual source can slot in without a schema
 * change. [description] is a short blurb when a source ever provides one.
 */
data class CharacterMeta(
    /** Character name. */
    val name: String,
    /** Short description, when a source provides one. */
    val description: String? = null,
)
