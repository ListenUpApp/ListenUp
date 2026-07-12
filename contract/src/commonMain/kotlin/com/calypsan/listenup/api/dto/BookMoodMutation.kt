package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The offline-first outbox payload for a book↔mood junction edit, riding the `book_moods`
 * outbox channel keyed by the synthetic `"$bookId:$moodId"` envelope id — the same id the
 * junction's mirror row uses — so the per-entity FIFO order and the anti-flicker shield align
 * with the row the edit changes. Mirrors [BookTagMutation].
 *
 * Only removal is modelled: adding a mood to a book is find-or-create and may mint a new
 * server-side mood id, so it cannot be mirrored optimistically and stays an online RPC
 * ([com.calypsan.listenup.api.MoodService.addMoodToBook]).
 */
@Serializable
sealed interface BookMoodMutation {
    /**
     * Remove the mood [moodId] from book [bookId] — maps to
     * [com.calypsan.listenup.api.MoodService.removeMoodFromBook]. Idempotent server-side.
     *
     * @property bookId the book losing the mood.
     * @property moodId the mood being removed.
     */
    @Serializable
    @SerialName("BookMoodMutation.Remove")
    data class Remove(
        @SerialName("bookId") val bookId: String,
        @SerialName("moodId") val moodId: String,
    ) : BookMoodMutation
}
