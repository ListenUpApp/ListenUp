package com.calypsan.listenup.server.transcode

import com.calypsan.listenup.api.dto.CodecCapability

/** What `prepare()` should hand this client for one file. */
enum class TranscodeDecision {
    /** Serve the original bytes — the default and the overwhelmingly common answer. */
    DirectPlay,

    /** Serve an HLS stream the server encodes on demand. */
    Transcode,

    /** The client cannot decode this, and we have no encoder. Serve the original, flagged honestly. */
    DirectPlayTranscoderUnavailable,
}

/**
 * Decides direct-play versus transcode for one file and one client. Pure — no I/O, no clock, no
 * config — so the whole decision table is a unit test rather than an integration one.
 */
class TranscodePolicy {
    /**
     * @param codec the file's container codec (`book_audio_files.codec`), e.g. `aac`, `mp3`.
     * @param profile the AAC object type (`codecProfile`), e.g. `lc`, `xhe`; null when unknown.
     * @param capabilities what the client declared. **Null means direct-play everything** (the
     *   legacy contract). An **empty set** is a different statement — "I can decode nothing I am
     *   sure of" — and transcodes, which is what a client whose codec enumeration failed sends.
     * @param force the client asked for a transcode regardless.
     * @param available whether a working encoder exists.
     */
    fun decide(
        codec: String,
        profile: String?,
        capabilities: Set<CodecCapability>?,
        force: Boolean,
        available: Boolean,
    ): TranscodeDecision {
        if (capabilities == null) return TranscodeDecision.DirectPlay
        if (force) return if (available) TranscodeDecision.Transcode else TranscodeDecision.DirectPlay
        val required = capabilityFor(codec, profile)
        if (required != null && required in capabilities) return TranscodeDecision.DirectPlay
        return if (available) TranscodeDecision.Transcode else TranscodeDecision.DirectPlayTranscoderUnavailable
    }

    /**
     * Maps a stored `(codec, profile)` pair onto the capability a client must declare.
     *
     * This is a mapping, not a comparison: `Mp4CodecExtractor` stores the codec and the profile
     * separately — `("aac", "lc")`, `("aac", "xhe")`, `("mp3", null)` — and never writes a composed
     * token like `aac_lc`, which is what [CodecCapability] speaks.
     *
     * An unknown AAC profile resolves to [CodecCapability.AAC_LC] — a real library has many rows
     * with no profile recorded, and AAC-LC is both the overwhelmingly likely truth and the safe
     * guess: guessing LC when the file is xHE costs one failed direct-play the client can retry
     * with `forceTranscode`, where guessing xHE would transcode most of a library for nothing.
     * A codec we do not recognise at all — `eac3`, which the extractor does emit — returns null,
     * which the caller treats as "transcode".
     */
    private fun capabilityFor(
        codec: String,
        profile: String?,
    ): CodecCapability? =
        when (codec.lowercase()) {
            "mp3" -> {
                CodecCapability.MP3
            }

            "flac" -> {
                CodecCapability.FLAC
            }

            "opus" -> {
                CodecCapability.OPUS
            }

            "vorbis" -> {
                CodecCapability.VORBIS
            }

            "alac" -> {
                CodecCapability.ALAC
            }

            "ac4" -> {
                CodecCapability.AC4
            }

            "aac" -> {
                when (profile?.lowercase()) {
                    "he" -> CodecCapability.AAC_HE
                    "hev2", "he_v2", "ps" -> CodecCapability.AAC_HE_V2
                    "xhe" -> CodecCapability.AAC_XHE
                    else -> CodecCapability.AAC_LC
                }
            }

            else -> {
                null
            }
        }
}
