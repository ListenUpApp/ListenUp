package com.calypsan.listenup.client.voice

/**
 * Structured hints from voice assistants.
 * Android: Extracted from onPlayFromSearch extras bundle
 * iOS: Extracted from INMediaSearch properties
 */
data class VoiceHints(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val focus: MediaFocus? = null,
)

/**
 * What type of media the user requested.
 * Maps to Android's EXTRA_MEDIA_FOCUS values.
 */
enum class MediaFocus {
    TITLE,
    ARTIST,
    ALBUM,
    UNSPECIFIED,
}
