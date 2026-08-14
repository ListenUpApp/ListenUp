package com.calypsan.listenup.api.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A codec a client can decode, declared by the client at `prepare()` so the server can decide
 * whether that client needs a transcoded stream.
 *
 * These are *capability* tokens, and each one names a codec and its profile together because that
 * pair is what decidability turns on — a browser that decodes AAC-LC decodes none of the xHE-AAC
 * that most of the reference library is encoded in.
 *
 * ⚠️ **They are not the shape the server stores.** `Mp4CodecExtractor` writes a codec and a profile
 * into two separate columns — `("aac", "lc")`, `("aac", "xhe")`, `("mp3", null)` — so nothing in
 * `book_audio_files` ever holds the string `aac_lc`. Mapping a stored pair onto one of these is the
 * transcode policy's job, and it is a real mapping, not a comparison. Two cases have no entry here
 * and must be decided rather than matched: a null profile (257 rows in the reference library), and
 * `eac3`, which the extractor emits and this enum does not name.
 *
 * ⛔ **A client that declares nothing (`null`) direct-plays everything** — that is the legacy
 * contract, bit-identical to the behaviour before transcoding existed, and it is what keeps old
 * clients working with no migration.
 */
@Serializable
enum class CodecCapability {
    @SerialName("mp3")
    MP3,

    @SerialName("aac_lc")
    AAC_LC,

    @SerialName("aac_he")
    AAC_HE,

    @SerialName("aac_he_v2")
    AAC_HE_V2,

    /** Extended HE-AAC (USAC). Device-dependent on Android; absent from Chrome and Firefox. */
    @SerialName("aac_xhe")
    AAC_XHE,

    @SerialName("flac")
    FLAC,

    @SerialName("opus")
    OPUS,

    @SerialName("vorbis")
    VORBIS,

    @SerialName("alac")
    ALAC,

    @SerialName("ac4")
    AC4,
}
