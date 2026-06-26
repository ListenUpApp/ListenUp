package com.calypsan.listenup.client.domain.model

import kotlinx.serialization.Serializable

/**
 * The flat (non-hierarchical) classification axis a facet-browse page lists books for.
 *
 * Genres are hierarchical and browse via their own materialized-path RPC; tags and moods are
 * flat, their `book_tags` / `book_moods` junctions are synced into Room, so books-by-facet is a
 * local Room query. [FacetKind] parameterizes the single facet-browse surface
 * ([com.calypsan.listenup.client.presentation.browsefacet.BrowseFacetViewModel]) over the two
 * flat axes — one screen, two looks — instead of two near-identical Tag/Mood screens.
 *
 * - [Tag] — community tropes / shelving descriptors (e.g. "Staff Pick", "Found Family").
 * - [Mood] — the affective axis, "how it feels" (e.g. "Atmospheric", "Tense").
 */
@Serializable
enum class FacetKind {
    Tag,
    Mood,
}
