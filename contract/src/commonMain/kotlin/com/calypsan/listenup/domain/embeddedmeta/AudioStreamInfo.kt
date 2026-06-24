package com.calypsan.listenup.domain.embeddedmeta

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Structured audio-stream technical metadata for the primary audio file.
 *
 * All fields nullable — a given format/file may not expose every value (e.g. a
 * codec with no AAC-style profile leaves [codecProfile] null). [codec] is a
 * canonical lower-case token (`aac`, `ac4`, `eac3`, `mp3`); [codecProfile] is the
 * AAC AOT-derived token (`lc`/`he`/`hev2`/`xhe`); [spatial] is `atmos` when the
 * stream carries Dolby spatial audio. Display strings are derived client-side.
 */
@Serializable
@SerialName("AudioStreamInfo")
data class AudioStreamInfo(
    val codec: String? = null,
    val codecProfile: String? = null,
    val spatial: String? = null,
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val channels: Int? = null,
)
