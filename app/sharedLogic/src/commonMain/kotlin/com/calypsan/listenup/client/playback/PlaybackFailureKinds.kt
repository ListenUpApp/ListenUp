package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.api.error.TransportError

/**
 * Failures a returning network can cure: the drop grace waits on these, the iOS engine keeps re-preparing through
 * them, and Retry re-probes the server for them.
 *
 * [PlaybackError.OfflineNotDownloaded] counts: a device that comes back online makes the book playable again.
 * [PlaybackError.CouldNotStart] does not: the preparer reports it only for a non-network cause (a missing book, a
 * timeline that can't be built), which waiting won't change.
 */
internal fun AppError.isCurableByNetwork(): Boolean =
    this is PlaybackError.ConnectionLost ||
        this is PlaybackError.Stalled ||
        this is PlaybackError.ServerUnreachable ||
        this is PlaybackError.OfflineNotDownloaded ||
        (this is TransportError && isRetryable)

/** The proactive reasons a book can't be reached right now (shown before Play, and explaining a surfaced drop). */
internal fun AppError?.isServerOutOfReach(): Boolean =
    this is PlaybackError.ServerUnreachable || this is PlaybackError.OfflineNotDownloaded
