package com.calypsan.listenup.client.domain.model

/**
 * Domain model for an audio file in a book.
 *
 * Used by PlaybackTimeline to build playback coordinates.
 * This is the domain representation - data layer mappers convert
 * from API responses to this type.
 */
data class AudioFile(
    /** Unique identifier for the audio file */
    val id: String,
    /** Zero-based play-order index within the book, from the on-disk track order. */
    val index: Int,
    /** Original filename */
    val filename: String,
    /** Audio format (e.g., "mp3", "m4b", "opus") */
    val format: String,
    /** Codec used for encoding (e.g., "aac", "mp3") */
    val codec: String,
    /** Duration in milliseconds */
    val duration: Long,
    /** File size in bytes */
    val size: Long,
)
