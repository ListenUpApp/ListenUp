package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.error.TranscodeError

/**
 * The player card's title for this error, in the browser's words. Web keeps no string catalog;
 * the native clients read the same sentences from `en.json` (`player.title_*`).
 */
internal fun AppError.playerTitle(): String =
    when (this) {
        is PlaybackError.ConnectionLost, is PlaybackError.Stalled, is PlaybackError.CouldNotStart -> "Couldn't play this book."
        is PlaybackError.ServerUnreachable -> "Can't reach your server."
        is PlaybackError.OfflineNotDownloaded -> "You're offline."
        is PlaybackError.UnsupportedFormat -> "Can't play this audio."
        is AuthError.SessionExpired -> "Your session expired. Please sign in again."
        is PlaybackError.NoLongerAvailable -> "No longer in your library."
        is BookError.HeldForReview -> "Waiting in the Inbox."
        is PlaybackError.BlockedInBackground -> "Tap to keep listening."
        is PlaybackError.AutoplayBlocked -> "Tap Play to start."
        is TranscodeError.TranscoderUnavailable -> "Can't play this book here."
        else -> message
    }

/** The one-line cause under the title, or null when the player has none for this error. */
internal fun AppError.playerCause(): String? =
    when (this) {
        is PlaybackError.ConnectionLost, is PlaybackError.Stalled -> "The connection to your server dropped."
        is PlaybackError.CouldNotStart -> "Something went wrong starting this book."
        is PlaybackError.ServerUnreachable -> "Books stream from your server in the browser."
        is PlaybackError.UnsupportedFormat -> "This device can't decode the book's audio format."
        is AuthError.SessionExpired -> "Downloaded books keep playing. Streaming needs you signed in."
        is PlaybackError.NoLongerAvailable -> "The book was removed, or your access changed."
        is BookError.HeldForReview -> "Release it from the Inbox before anyone can play it."
        is PlaybackError.AutoplayBlocked -> "Your browser needs a tap before it plays sound."
        is TranscodeError.TranscoderUnavailable -> SERVER_CANNOT_CONVERT_CAUSE
        else -> null
    }

/** Too long for one `when` line; the same sentence as `player.cause_transcode_unavailable` in `en.json`. */
private const val SERVER_CANNOT_CONVERT_CAUSE =
    "This device can't play its audio format, and your server can't convert it. Try another device, or ask " +
        "whoever runs your server to set up audio conversion."
