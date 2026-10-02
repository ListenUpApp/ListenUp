package com.calypsan.listenup.client.data.local.db

import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.core.BookId

/**
 * The refusal for acting on a book held for review, or `null` when it may be played or downloaded.
 *
 * A held book is triage-only (open, edit, release). The gates that would otherwise act on it — the
 * playback choke point ([com.calypsan.listenup.client.playback.PlaybackPreparer.prepare]) and both
 * platform download backends — all ask here, so they refuse with the same typed value. Always null on
 * a member's device, which never holds INBOX rows.
 */
internal suspend fun BookDao.heldRefusal(bookId: BookId): BookError.HeldForReview? =
    if (isHeld(bookId)) BookError.HeldForReview(debugInfo = "bookId=${bookId.value}") else null
