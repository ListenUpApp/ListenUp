package com.calypsan.listenup.client.playback

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import com.calypsan.listenup.api.dto.CodecCapability

/**
 * Queried from `MediaCodecList`, never assumed from the OS version: xHE-AAC decode is a per-device
 * property on Android, and guessing it wrong either strands a listener or wastes a transcode.
 *
 * **A failed enumeration answers "nothing", never throws.** This runs on the play path, so an
 * escaping exception would strand playback outright on any device whose codec list cannot be read.
 * Empty is the safe direction and not a guess: it claims no codec the device might not have, and
 * the server responds by transcoding, which still plays. Declaring a codec we cannot decode is the
 * failure that ends in silence.
 */
actual fun platformCodecCapabilities(): Set<CodecCapability> {
    val decoders =
        runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }
            .getOrNull()
            ?.filter { !it.isEncoder }
            ?: return emptySet()
    val out = mutableSetOf<CodecCapability>()
    for (info in decoders) {
        for (type in info.supportedTypes) {
            when (type.lowercase()) {
                "audio/mpeg" -> out += CodecCapability.MP3
                "audio/flac" -> out += CodecCapability.FLAC
                "audio/opus" -> out += CodecCapability.OPUS
                "audio/vorbis" -> out += CodecCapability.VORBIS
                "audio/ac4" -> out += CodecCapability.AC4
                "audio/mp4a-latm" -> out += aacProfilesOf(info, type)
            }
        }
    }
    return out
}

/** AAC is one MIME type covering several object types, so the profile levels are the real answer. */
private fun aacProfilesOf(
    info: MediaCodecInfo,
    type: String,
): Set<CodecCapability> {
    val levels = runCatching { info.getCapabilitiesForType(type).profileLevels }.getOrNull().orEmpty()
    val out = mutableSetOf(CodecCapability.AAC_LC)
    for (level in levels) {
        when (level.profile) {
            MediaCodecInfo.CodecProfileLevel.AACObjectHE -> out += CodecCapability.AAC_HE
            MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS -> out += CodecCapability.AAC_HE_V2
            MediaCodecInfo.CodecProfileLevel.AACObjectXHE -> out += CodecCapability.AAC_XHE
        }
    }
    return out
}
