package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The offline-first outbox payload for a genre lifecycle edit, riding the `genres`
 * outbox channel keyed by the genre's id.
 *
 * Each variant carries exactly the arguments its backing [com.calypsan.listenup.api.GenreService]
 * method needs. Both variants are last-write-wins / idempotent, so the channel is safe to re-fire.
 * Creating a genre is intentionally NOT modelled here: the server mints the genre's id and slug, so
 * it cannot be mirrored optimistically and stays an online RPC
 * ([com.calypsan.listenup.api.GenreService.createGenre]). A subtree reparent
 * ([com.calypsan.listenup.api.GenreService.moveGenre]) and a merge
 * ([com.calypsan.listenup.api.GenreService.mergeGenres]) also stay online — a move recomputes
 * materialized path + depth across every descendant, and a merge relinks junctions server-side.
 * Mirrors [TagMutation] / [CollectionMutation].
 */
@Serializable
sealed interface GenreMutation {
    /**
     * A metadata PATCH — maps to [com.calypsan.listenup.api.GenreService.updateGenre]. Only the
     * genre's name/sortOrder are mirrored client-side (the local genre mirror carries no
     * description/color column); the full patch still crosses the wire for the server to apply.
     *
     * @property patch the per-field PATCH; null fields leave existing state untouched.
     */
    @Serializable
    @SerialName("GenreMutation.Update")
    data class Update(
        @SerialName("patch") val patch: GenreUpdate,
    ) : GenreMutation

    /**
     * Delete the genre and cascade its `book_genres` links — maps to
     * [com.calypsan.listenup.api.GenreService.deleteGenre]. The server refuses a genre with live
     * descendants, so the client pre-validates the same rule before enqueuing.
     */
    @Serializable
    @SerialName("GenreMutation.Delete")
    data object Delete : GenreMutation
}
