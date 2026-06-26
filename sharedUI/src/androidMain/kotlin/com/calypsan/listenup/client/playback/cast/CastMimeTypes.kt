package com.calypsan.listenup.client.playback.cast

/**
 * Maps a stored audio [format] token (lowercase extension, e.g. `"m4b"`) to the
 * MIME type a Cast receiver needs in the load request. Returns `null` for
 * formats Chromecast cannot decode (WMA, AIFF) so callers can fall back to local
 * playback rather than hand the receiver something it will reject.
 *
 * Cast codec support: FLAC, HE/LC-AAC (in MP4), MP3, Opus/Vorbis (in OGG/WebM),
 * WAV (LPCM). See https://developers.google.com/cast/docs/media.
 */
fun castMimeType(format: String): String? =
    when (format.lowercase()) {
        "m4b", "m4a", "mp4", "m4p" -> "audio/mp4"
        "aac" -> "audio/aac"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "wav", "wave" -> "audio/wav"
        "ogg", "oga", "opus" -> "audio/ogg"
        "webm" -> "audio/webm"
        else -> null
    }
