package com.calypsan.listenup.domain.embeddedmeta

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Where the parser found the chapter list inside an audio file.
 *
 * Diagnostic provenance — surfaces in logs and metrics so a "wrong chapters"
 * report immediately tells you which extraction path produced them.
 *
 * Per spec §8.4, MP4 dual-emission (Nero + Apple) prefers Nero, so the
 * source value reflects that precedence outcome, not all sources present.
 */
@Serializable
sealed interface ChapterSource {
    @Serializable @SerialName("ChapterSource.Mp4Chpl") data object Mp4Chpl : ChapterSource

    @Serializable @SerialName("ChapterSource.Mp4TextTrack") data object Mp4TextTrack : ChapterSource

    @Serializable @SerialName("ChapterSource.Id3v2Chap") data object Id3v2Chap : ChapterSource

    @Serializable @SerialName("ChapterSource.FlacCuesheet") data object FlacCuesheet : ChapterSource

    @Serializable @SerialName("ChapterSource.OggVorbisComment") data object OggVorbisComment : ChapterSource

    @Serializable @SerialName("ChapterSource.None") data object None : ChapterSource
}
