package com.calypsan.listenup.domain.embeddedmeta

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Cover artwork extracted from an audio file's metadata area.
 *
 * Sources per format:
 * - MP3: ID3v2 `APIC` frame
 * - FLAC: `PICTURE` metadata block (preferring picture-type 3 "Cover (front)")
 * - MP4: `moov.udta.meta.ilst.covr.data` atom
 * - Ogg/Opus: `METADATA_BLOCK_PICTURE` Vorbis comment field (base64-encoded FLAC PICTURE)
 *
 * [equals] / [hashCode] override the default `data class` reference-equality
 * for [bytes] so two artworks with identical content compare equal.
 */
@Serializable
@SerialName("EmbeddedArtwork")
data class EmbeddedArtwork(
    val mime: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddedArtwork) return false
        return mime == other.mime && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = mime.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}
