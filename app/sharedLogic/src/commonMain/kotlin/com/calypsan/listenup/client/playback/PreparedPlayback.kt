package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline

/**
 * Everything a player needs to begin playback of a book, as an immutable value.
 *
 * Unlike [PlaybackManager.PrepareResult] this also carries the resolved
 * [chapters] list, so callers that do not hold a [PlaybackManager] — the iOS
 * native player — get chapters without issuing a second query.
 */
data class PreparedPlayback(
    val timeline: PlaybackTimeline,
    val chapters: List<Chapter>,
    val bookTitle: String,
    val bookAuthor: String,
    val bookNarrator: String,
    val seriesName: String?,
    val coverPath: String?,
    val resumePositionMs: Long,
    val resumeSpeed: Float,
    /** Boost to start playback with: the book's custom boost, else the global default. */
    val resumeBoostDb: Float,
    /** Client-measured R128 gain for this book (synced), null until measured. */
    val measuredGainDb: Float?,
    /** Server tag-read normalization gain (ReplayGain/iTunNORM), null when the file has no tag. */
    val normalizationGainDb: Float?,
    // Navigation targets for the player's overflow menu ("Go to Series / Author / Narrator"). New
    // fields default so existing constructors (playback-prep + test fixtures) stay source-compatible.
    val seriesId: String? = null,
    val authors: List<PlaybackNavRef> = emptyList(),
    val narrators: List<PlaybackNavRef> = emptyList(),
    // Content hash of the current cover, so the player surfaces (mini player, now-playing, full
    // screen) content-address the cover and refresh it after a re-scrape instead of serving the
    // stale id-stable local file. Defaults for source-compat with existing constructors/fixtures.
    val coverHash: String? = null,
    /** Whether the server could convert every file this device can't play; a downloaded book never asks it. */
    val serverConversion: ServerConversion = ServerConversion.COVERED,
)

/**
 * A navigable reference the native player's overflow menu turns into a "Go to …" action: the
 * series or an individual author/narrator behind the currently-playing book.
 *
 * [name] is the credited display name (so it matches what the player shows); [id] is the
 * series/contributor identifier the menu navigates to.
 */
data class PlaybackNavRef(
    val id: String,
    val name: String,
)
