package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The offline-first outbox payload for a tag lifecycle edit, riding the `tags`
 * outbox channel keyed by the tag's id.
 *
 * Each variant carries exactly the arguments its backing [com.calypsan.listenup.api.TagService]
 * method needs. Both variants are last-write-wins / idempotent, so the channel is safe to re-fire.
 * Applying a tag to a book (find-or-create) is intentionally NOT modelled here: minting a new tag
 * allocates its id and slug server-side, so it cannot be mirrored optimistically and stays an online
 * RPC ([com.calypsan.listenup.api.TagService.addTagToBook]).
 */
@Serializable
sealed interface TagMutation {
    /**
     * Rename the tag to [newName] — maps to [com.calypsan.listenup.api.TagService.renameTag]. The
     * slug is intentionally preserved server-side; only the display name changes.
     *
     * @property newName the new display name.
     */
    @Serializable
    @SerialName("TagMutation.Rename")
    data class Rename(
        @SerialName("newName") val newName: String,
    ) : TagMutation

    /**
     * Delete the tag and cascade-tombstone its `book_tags` junctions — maps to
     * [com.calypsan.listenup.api.TagService.deleteTag].
     */
    @Serializable
    @SerialName("TagMutation.Delete")
    data object Delete : TagMutation
}
