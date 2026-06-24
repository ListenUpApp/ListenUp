package com.calypsan.listenup.server.embeddedmeta

import com.calypsan.listenup.domain.embeddedmeta.AudioFormat

/**
 * Sniff the first 16 bytes of an audio file to determine the container format.
 *
 * Recognises every magic-byte signature the embeddedmeta package may encounter,
 * even when no parser is registered for the format yet — this lets the scan
 * summary surface "12 FLAC files, parser not yet available" instead of an
 * opaque "unrecognised file" error. Codec disambiguation between Ogg/Vorbis
 * and Ogg/Opus is the parser's responsibility (it inspects the first packet
 * inside the first Ogg page); the detector returns [AudioFormat.Ogg] for both.
 *
 * Returns `null` when no magic matched — callers convert this to
 * [com.calypsan.listenup.api.error.AudioMetadataError.UnsupportedFormat]
 * with `format = null`.
 */
internal class AudioFormatDetector {
    private val mp3SyncMaskHi: Int = 0xE0

    @Suppress("MagicNumber")
    fun detect(headerBytes: ByteArray): AudioFormat? {
        require(headerBytes.size >= MIN_HEADER_BYTES) { "need at least $MIN_HEADER_BYTES bytes to detect format" }

        // ID3v2 prefix: "ID3"
        if (headerBytes[0] == 0x49.toByte() &&
            headerBytes[1] == 0x44.toByte() &&
            headerBytes[2] == 0x33.toByte()
        ) {
            return AudioFormat.Mp3
        }

        // FLAC: "fLaC"
        if (headerBytes[0] == 0x66.toByte() &&
            headerBytes[1] == 0x4C.toByte() &&
            headerBytes[2] == 0x61.toByte() &&
            headerBytes[3] == 0x43.toByte()
        ) {
            return AudioFormat.Flac
        }

        // Ogg: "OggS"
        if (headerBytes[0] == 0x4F.toByte() &&
            headerBytes[1] == 0x67.toByte() &&
            headerBytes[2] == 0x67.toByte() &&
            headerBytes[3] == 0x53.toByte()
        ) {
            return AudioFormat.Ogg
        }

        // MP4: "ftyp" at bytes 4..7
        if (headerBytes[4] == 0x66.toByte() &&
            headerBytes[5] == 0x74.toByte() &&
            headerBytes[6] == 0x79.toByte() &&
            headerBytes[7] == 0x70.toByte()
        ) {
            return AudioFormat.Mp4
        }

        // Bare MP3: 11-bit MPEG sync. First byte 0xFF, next byte's top 3 bits = 0b111.
        if (headerBytes[0] == 0xFF.toByte() &&
            (headerBytes[1].toInt() and mp3SyncMaskHi) == mp3SyncMaskHi
        ) {
            return AudioFormat.Mp3
        }

        return null
    }

    companion object {
        const val MIN_HEADER_BYTES: Int = 16
    }
}
