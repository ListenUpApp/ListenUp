package com.calypsan.listenup.domain.embeddedmeta

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Canonical container format for an audio file.
 *
 * Detected by [com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector]
 * sniffing the leading magic bytes. Comprehensive — enumerates every format
 * the detector can recognise, regardless of whether a parser is currently
 * registered for it. The parser registry (Koin-injected `List<AudioFormatParser>`)
 * decides which formats actually parse to structured metadata; unrecognised
 * formats surface as `AudioMetadataError.UnsupportedFormat`.
 *
 * `Mp4` covers both M4A and M4B audiobooks — the brand inside the `ftyp`
 * atom is recorded in the parser logs but doesn't change the parser path.
 */
@Serializable
sealed interface AudioFormat {
    @Serializable @SerialName("AudioFormat.Mp3") data object Mp3 : AudioFormat

    @Serializable @SerialName("AudioFormat.Flac") data object Flac : AudioFormat

    @Serializable @SerialName("AudioFormat.Mp4") data object Mp4 : AudioFormat

    @Serializable @SerialName("AudioFormat.Ogg") data object Ogg : AudioFormat

    @Serializable @SerialName("AudioFormat.Opus") data object Opus : AudioFormat
}
