package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The offline-first outbox payload for a book↔tag junction edit, riding the `book_tags`
 * outbox channel keyed by the synthetic `"$bookId:$tagId"` envelope id — the same id the
 * junction's mirror row uses — so the per-entity FIFO order and the anti-flicker shield align
 * with the row the edit changes.
 *
 * Both add and remove are modelled, but add is only enqueued for the **name-hit** case: adding a tag
 * to a book is find-or-create by slug server-side, and the client has no cross-platform slug
 * normalizer. When a tag with the same display name (case-insensitive) already exists locally its
 * slug is `normalize(name)`, so the server's find-or-create for the same `name` resolves to that same
 * tag id — the client can optimistically add the `([bookId], [Add.tagId])` junction and enqueue the
 * add, guaranteed to converge with the echo. A genuinely-new tag (no same-name row) mints its id/slug
 * server-side and stays an online RPC ([com.calypsan.listenup.api.TagService.addTagToBook]); it is
 * never enqueued as an [Add].
 */
@Serializable
sealed interface BookTagMutation {
    /**
     * Add the existing tag [tagId] to book [bookId] — maps to
     * [com.calypsan.listenup.api.TagService.addTagToBook], whose find-or-create resolves [name] back to
     * this same tag. Only enqueued when a same-name tag already exists locally (see the interface KDoc);
     * idempotent server-side (re-adding an existing junction returns Success).
     *
     * @property bookId the book gaining the tag.
     * @property tagId the existing tag being added.
     * @property name the tag's display name — the find-or-create argument the RPC takes.
     */
    @Serializable
    @SerialName("BookTagMutation.Add")
    data class Add(
        @SerialName("bookId") val bookId: String,
        @SerialName("tagId") val tagId: String,
        @SerialName("name") val name: String,
    ) : BookTagMutation

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
