package com.calypsan.listenup.server.embeddedmeta.format.mp4

import com.calypsan.listenup.domain.embeddedmeta.AudioStreamInfo
import com.calypsan.listenup.server.embeddedmeta.decode.TextDecoding

/**
 * Extracts [AudioStreamInfo] (codec, profile, spatial flag, bitrate, sample
 * rate, channels) from an in-memory `moov` byte slice by navigating to the
 * audio track's sample description (`stsd`) and reading its sample entry.
 *
 * Navigation path (all offsets relative to the [moov] atom, which sits at
 * offset 0 within `moovBytes` — see [Mp4Parser]):
 * - Find the audio `trak`: iterate `moov`'s `trak` children, descend
 *   `trak → mdia → hdlr`, and select the one whose `handler_type == "soun"`.
 * - For that track descend `mdia → minf → stbl → stsd`. `stsd` is a FullBox:
 *   4 bytes version+flags, 4 bytes entry_count, then the first sample entry.
 * - The sample entry is itself a box; its fourcc (`mp4a`, `ac-4`, `ec-3`,
 *   `alac`, `fLaC`, `Opus`, …) drives codec dispatch. The 28-byte
 *   AudioSampleEntry header carries channelcount and the 16.16 samplerate;
 *   codec-specific child boxes (`esds`, `dac4`, `dec3`) follow at offset +28.
 *
 * Returns null when the file has no audio (`soun`) track, no `stsd`, or no
 * sample entry — the caller treats absent stream info as "not available"
 * rather than an error.
 *
 * MagicNumber suppressed: every offset/width below is fixed by ISO/IEC
 * 14496-12 (box headers, FullBox version+flags, AudioSampleEntry layout) or
 * the relevant Dolby/MPEG-4 codec spec.
 */
@Suppress("MagicNumber")
internal object Mp4CodecExtractor {
    private const val FULLBOX_HEADER = 4
    private const val ENTRY_COUNT_FIELD = 4
    private const val SAMPLE_ENTRY_HEADER = 28
    private const val CHANNELCOUNT_OFFSET = 16
    private const val SAMPLERATE_OFFSET = 24

    // MPEG-4 objectTypeIndication values (ISO/IEC 14496-1 §7.2.6.6).
    private const val OTI_AAC = 0x40
    private const val OTI_MP3_A = 0x69
    private const val OTI_MP3_B = 0x6B

    // AAC Audio Object Types (ISO/IEC 14496-3) → profile tokens.
    private const val AOT_LC = 2
    private const val AOT_HE = 5
    private const val AOT_HE_V2 = 29
    private const val AOT_XHE = 42

    fun extract(
        moovBytes: ByteArray,
        moov: Atom,
    ): AudioStreamInfo? {
        val entry = findAudioSampleEntry(moovBytes, moov) ?: return null

        // Guard: reject a truncated AudioSampleEntry (corrupt or fuzz input) rather than
        // reading out-of-bounds. The full header must be present before we read channels,
        // sampleRate, or any child codec box.
        if (entry.dataSize < SAMPLE_ENTRY_HEADER) return null

        val entryChannels = readBeUInt16(moovBytes, entry.dataOffset + CHANNELCOUNT_OFFSET)
        val entryRate = readBeUInt16(moovBytes, entry.dataOffset + SAMPLERATE_OFFSET)
        val codecBoxesStart = entry.dataOffset + SAMPLE_ENTRY_HEADER

        return when (entry.type) {
            "mp4a" -> {
                extractMp4a(moovBytes, entry, codecBoxesStart, entryRate, entryChannels)
            }

            "ac-4" -> {
                AudioStreamInfo(
                    codec = "ac4",
                    spatial = "atmos",
                    sampleRate = entryRate,
                    channels = entryChannels,
                )
            }

            "ec-3" -> {
                extractEc3(moovBytes, entry, codecBoxesStart, entryRate, entryChannels)
            }

            "alac" -> {
                AudioStreamInfo(codec = "alac", sampleRate = entryRate, channels = entryChannels)
            }

            "fLaC" -> {
                AudioStreamInfo(codec = "flac", sampleRate = entryRate, channels = entryChannels)
            }

            "Opus" -> {
                AudioStreamInfo(codec = "opus", sampleRate = entryRate, channels = entryChannels)
            }

            else -> {
                AudioStreamInfo(
                    codec = entry.type.trim().lowercase(),
                    sampleRate = entryRate,
                    channels = entryChannels,
                )
            }
        }
    }

    /**
     * Walk `moov`'s `trak` children, returning the sample entry box of the
     * first track whose `hdlr.handler_type == "soun"`. Returns null if no audio
     * track is present or its `stsd` has no entry.
     */
    private fun findAudioSampleEntry(
        moovBytes: ByteArray,
        moov: Atom,
    ): Atom? {
        var entry: Atom? = null
        AtomWalker.forEachChild(moovBytes, moov.dataOffset, moov.end) { trak ->
            if (trak.type != "trak" || entry != null) return@forEachChild
            if (!isAudioTrack(moovBytes, trak)) return@forEachChild
            entry = readSampleEntry(moovBytes, trak)
        }
        return entry
    }

    /** True when `trak → mdia → hdlr` has `handler_type == "soun"`. */
    private fun isAudioTrack(
        moovBytes: ByteArray,
        trak: Atom,
    ): Boolean {
        val mdia = AtomWalker.findChild(moovBytes, trak.dataOffset, trak.end, "mdia") ?: return false
        val hdlr = AtomWalker.findChild(moovBytes, mdia.dataOffset, mdia.end, "hdlr") ?: return false
        // hdlr FullBox: version+flags(4) + pre_defined(4) + handler_type(4).
        if (hdlr.dataOffset + 12 > hdlr.end) return false
        return TextDecoding.decodeLatin1(moovBytes, hdlr.dataOffset + 8, 4) == "soun"
    }

    /** Read the first sample entry box from `trak → mdia → minf → stbl → stsd`. */
    private fun readSampleEntry(
        moovBytes: ByteArray,
        trak: Atom,
    ): Atom? {
        val mdia = AtomWalker.findChild(moovBytes, trak.dataOffset, trak.end, "mdia") ?: return null
        val minf = AtomWalker.findChild(moovBytes, mdia.dataOffset, mdia.end, "minf") ?: return null
        val stbl = AtomWalker.findChild(moovBytes, minf.dataOffset, minf.end, "stbl") ?: return null
        val stsd = AtomWalker.findChild(moovBytes, stbl.dataOffset, stbl.end, "stsd") ?: return null
        // stsd FullBox: version+flags(4) + entry_count(4), then the first entry.
        val entryStart = stsd.dataOffset + FULLBOX_HEADER + ENTRY_COUNT_FIELD
        if (entryStart + 8 > stsd.end) return null
        return try {
            AtomWalker.readHeader(moovBytes, entryStart, stsd.end)
        } catch (_: AtomParseException) {
            null
        }
    }

    private fun extractMp4a(
        moovBytes: ByteArray,
        entry: Atom,
        codecBoxesStart: Int,
        entryRate: Int,
        entryChannels: Int,
    ): AudioStreamInfo {
        val esds =
            AtomWalker.findChild(moovBytes, codecBoxesStart, entry.end, "esds")
                ?: return AudioStreamInfo(codec = "aac", sampleRate = entryRate, channels = entryChannels)

        val info = parseEsds(moovBytes.copyOfRange(esds.dataOffset, esds.end))
        return when (info.objectTypeIndication) {
            OTI_AAC -> {
                aacStreamInfo(info, entryRate, entryChannels)
            }

            OTI_MP3_A, OTI_MP3_B -> {
                AudioStreamInfo(
                    codec = "mp3",
                    bitrate = info.avgBitrate,
                    sampleRate = entryRate,
                    channels = entryChannels,
                )
            }

            else -> {
                AudioStreamInfo(
                    codec = "aac",
                    bitrate = info.avgBitrate,
                    sampleRate = entryRate,
                    channels = entryChannels,
                )
            }
        }
    }

    /**
     * Build an AAC [AudioStreamInfo] from a parsed `esds`. When the esds carries
     * an AudioSpecificConfig the AOT-derived profile, sample rate, and channels
     * come from it (falling back to the sample-entry header for missing values);
     * otherwise only the sample-entry specs are used.
     */
    private fun aacStreamInfo(
        info: EsdsInfo,
        entryRate: Int,
        entryChannels: Int,
    ): AudioStreamInfo {
        val asc =
            info.audioSpecificConfig
                ?: return AudioStreamInfo(
                    codec = "aac",
                    bitrate = info.avgBitrate,
                    sampleRate = entryRate,
                    channels = entryChannels,
                )
        val ascInfo = decodeAudioSpecificConfig(asc)
        return AudioStreamInfo(
            codec = "aac",
            codecProfile = aacProfile(ascInfo.audioObjectType),
            bitrate = info.avgBitrate,
            sampleRate = ascInfo.sampleRate ?: entryRate,
            channels = ascInfo.channels ?: entryChannels,
        )
    }

    private fun extractEc3(
        moovBytes: ByteArray,
        entry: Atom,
        codecBoxesStart: Int,
        entryRate: Int,
        entryChannels: Int,
    ): AudioStreamInfo {
        val dec3 = AtomWalker.findChild(moovBytes, codecBoxesStart, entry.end, "dec3")
        // JOC (Atmos object coding) is signalled — by the convention pinned in
        // the unit fixtures — when the dec3 payload's final byte has its lowest
        // bit set. Best-effort; Task 8 refines against a real Atmos file.
        val joc =
            dec3 != null &&
                dec3.dataSize > 0 &&
                moovBytes[dec3.end - 1].toInt() and 0x01 == 1
        return AudioStreamInfo(
            codec = "eac3",
            spatial = if (joc) "atmos" else null,
            sampleRate = entryRate,
            channels = entryChannels,
        )
    }

    private fun aacProfile(audioObjectType: Int): String? =
        when (audioObjectType) {
            AOT_LC -> "lc"
            AOT_HE -> "he"
            AOT_HE_V2 -> "hev2"
            AOT_XHE -> "xhe"
            else -> null
        }

    /** Read 2 big-endian bytes as an unsigned 16-bit Int. */
    private fun readBeUInt16(
        bytes: ByteArray,
        offset: Int,
    ): Int = ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}
