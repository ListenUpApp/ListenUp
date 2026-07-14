package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The offline-first outbox payload for a collection lifecycle edit, riding the `collections`
 * outbox channel keyed by the collection's id.
 *
 * Each variant carries exactly the arguments its backing [com.calypsan.listenup.api.CollectionService]
 * method needs. Both variants are last-write-wins / idempotent, so the channel is safe to re-fire.
 * Creating a collection is intentionally NOT modelled here: the server mints the collection's id, so
 * it cannot be mirrored optimistically and stays an online RPC
 * ([com.calypsan.listenup.api.CollectionService.createCollection]). Mirrors [ShelfMutation].
 */
@Serializable
sealed interface CollectionMutation {
    /**
     * Rename the collection to [newName] — maps to
     * [com.calypsan.listenup.api.CollectionService.renameCollection].
     *
     * @property newName the new display name.
     */
    @Serializable
    @SerialName("CollectionMutation.Rename")
    data class Rename(
        @SerialName("newName") val newName: String,
    ) : CollectionMutation

    /**
     * Delete the collection and cascade-tombstone its `collection_books` junctions — maps to
     * [com.calypsan.listenup.api.CollectionService.deleteCollection].
     */
    @Serializable
    @SerialName("CollectionMutation.Delete")
    data object Delete : CollectionMutation
}
