package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The offline-first outbox payload for a shelf lifecycle edit, riding the `shelves`
 * outbox channel keyed by the shelf's id.
 *
 * Each variant carries exactly the arguments its backing [com.calypsan.listenup.api.ShelfService]
 * method needs. Both variants are last-write-wins / idempotent, so the channel is safe to re-fire.
 * Creating a shelf is intentionally NOT modelled here: the server mints the shelf's id and slug, so
 * it cannot be mirrored optimistically and stays an online RPC
 * ([com.calypsan.listenup.api.ShelfService.createShelf]). Mirrors [CollectionMutation].
 */
@Serializable
sealed interface ShelfMutation {
    /**
     * Update the shelf's display name, description, and privacy flag — maps to
     * [com.calypsan.listenup.api.ShelfService.updateShelf].
     *
     * @property name the new display name.
     * @property description the new description ("" when cleared).
     * @property isPrivate the new privacy flag.
     */
    @Serializable
    @SerialName("ShelfMutation.Update")
    data class Update(
        @SerialName("name") val name: String,
        @SerialName("description") val description: String,
        @SerialName("isPrivate") val isPrivate: Boolean,
    ) : ShelfMutation

    /**
     * Delete the shelf and cascade-tombstone its `shelf_books` junctions — maps to
     * [com.calypsan.listenup.api.ShelfService.deleteShelf].
     */
    @Serializable
    @SerialName("ShelfMutation.Delete")
    data object Delete : ShelfMutation
}
