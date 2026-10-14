package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.core.BookId

/**
 * The book a transport position may be saved under, or null when it belongs to none: [activeBookId] only
 * while [timeline] is that book's and the transport plays one of its files ([transportMediaId]).
 *
 * The manager names the next book as soon as it is activated, but Media3 applies the new queue some time
 * later. Until then the player still holds the outgoing book at the outgoing book's place, and a pause, tick
 * or error landing in that window would file that place under the new book: a book never played would
 * suddenly sit hours in. A media id is the only evidence of which book the player holds, so its absence
 * changes nothing.
 */
internal fun positionOwner(
    activeBookId: BookId?,
    timeline: PlaybackTimeline?,
    transportMediaId: String?,
): BookId? {
    if (activeBookId == null || timeline == null) return activeBookId
    if (timeline.bookId != activeBookId) return null
    if (transportMediaId.isNullOrEmpty()) return activeBookId
    val holdsThisBook = timeline.files.any { it.audioFileId == transportMediaId }
    return if (holdsThisBook) activeBookId else null
}
