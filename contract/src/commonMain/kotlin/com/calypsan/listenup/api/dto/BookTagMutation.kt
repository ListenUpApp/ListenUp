package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The offline-first outbox payload for a book↔tag junction edit, riding the `book_tags`
 * outbox channel keyed by the synthetic `"$bookId:$tagId"` envelope id — the same id the
 * junction's mirror row uses — so the per-entity FIFO order and the anti-flicker shield align
 * with the row the edit changes.
 *
 * Only removal is modelled: adding a tag to a book is find-or-create and may mint a new
 * server-side tag id, so it cannot be mirrored optimistically and stays an online RPC
 * ([com.calypsan.listenup.api.TagService.addTagToBook]).
 */
@Serializable
sealed interface BookTagMutation {
    /**
     * Remove the tag [tagId] from book [bookId] — maps to
     * [com.calypsan.listenup.api.TagService.removeTagFromBook]. Idempotent server-side.
     *
     * @property bookId the book losing the tag.
     * @property tagId the tag being removed.
     */
    @Serializable
    @SerialName("BookTagMutation.Remove")
    data class Remove(
        @SerialName("bookId") val bookId: String,
        @SerialName("tagId") val tagId: String,
    ) : BookTagMutation
}
