package com.calypsan.listenup.client.domain.model

/**
 * Domain model for an audio file in a book.
 *
 * Used by PlaybackTimeline to build playback coordinates.
 * This is the domain representation — data layer mappers convert
 * from Room entities to this type.
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
    /** AAC profile token (`lc`/`he`/`hev2`/`xhe`); null for non-AAC or when undetermined. */
    val codecProfile: String? = null,
    /** Spatial-audio marker (e.g. `atmos`); null when not spatial. */
    val spatial: String? = null,
    /** Average bitrate in bits/sec; null when undetermined. */
    val bitrate: Int? = null,
    /** Sample rate in Hz; null when undetermined. */
    val sampleRate: Int? = null,
    /** Channel count; null when undetermined. */
    val channels: Int? = null,
)
