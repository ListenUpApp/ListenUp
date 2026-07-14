package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The offline-first outbox payload for a book↔mood junction edit, riding the `book_moods`
 * outbox channel keyed by the synthetic `"$bookId:$moodId"` envelope id — the same id the
 * junction's mirror row uses — so the per-entity FIFO order and the anti-flicker shield align
 * with the row the edit changes. Mirrors [BookTagMutation].
 *
 * Both add and remove are modelled, but add is only enqueued for the **name-hit** case: adding a mood
 * to a book is find-or-create by slug server-side, and the client has no cross-platform slug
 * normalizer. When a mood with the same display name (case-insensitive) already exists locally its
 * slug is `normalize(name)`, so the server's find-or-create for the same `name` resolves to that same
 * mood id — the client can optimistically add the `([bookId], [Add.moodId])` junction and enqueue the
 * add, guaranteed to converge with the echo. A genuinely-new mood (no same-name row) mints its
 * id/slug server-side and stays an online RPC ([com.calypsan.listenup.api.MoodService.addMoodToBook]);
 * it is never enqueued as an [Add].
 */
@Serializable
sealed interface BookMoodMutation {
    /**
     * Add the existing mood [moodId] to book [bookId] — maps to
     * [com.calypsan.listenup.api.MoodService.addMoodToBook], whose find-or-create resolves [name] back
     * to this same mood. Only enqueued when a same-name mood already exists locally (see the interface
     * KDoc); idempotent server-side (re-adding an existing junction returns Success).
     *
     * @property bookId the book gaining the mood.
     * @property moodId the existing mood being added.
     * @property name the mood's display name — the find-or-create argument the RPC takes.
     */
    @Serializable
    @SerialName("BookMoodMutation.Add")
    data class Add(
        @SerialName("bookId") val bookId: String,
        @SerialName("moodId") val moodId: String,
        @SerialName("name") val name: String,
    ) : BookMoodMutation

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
