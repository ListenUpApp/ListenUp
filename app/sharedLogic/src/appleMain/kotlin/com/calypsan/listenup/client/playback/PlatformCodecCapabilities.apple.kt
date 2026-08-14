package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.dto.CodecCapability

/**
 * iOS 17+ / macOS: the AAC family including xHE-AAC, plus FLAC and ALAC. Vorbis is absent and Opus
 * is patchy in AVFoundation, so neither is declared — an undeclared codec costs a transcode, while
 * a wrongly declared one costs a listener silence.
 */
actual fun platformCodecCapabilities(): Set<CodecCapability> =
    setOf(
        CodecCapability.MP3,
        CodecCapability.AAC_LC,
        CodecCapability.AAC_HE,
        CodecCapability.AAC_HE_V2,
        CodecCapability.AAC_XHE,
        CodecCapability.FLAC,
        CodecCapability.ALAC,
    )
