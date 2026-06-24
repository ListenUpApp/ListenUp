package com.calypsan.listenup.server.embeddedmeta.format.mp4

internal data class AscInfo(
    val audioObjectType: Int,
    val sampleRate: Int?,
    val channels: Int?,
)

private val SAMPLE_RATE_TABLE =
    intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

private const val EXPLICIT_FREQ_INDEX = 15
private const val AOT_ESCAPE = 31

/**
 * Decode the AAC AudioSpecificConfig prefix: AOT, sample rate, channel count.
 *
 * MagicNumber suppressed: field widths (5-bit AOT, 6-bit escape extension,
 * 4-bit frequency index, 24-bit explicit rate, 4-bit channel config) and the
 * escape offset (32) are fixed by ISO/IEC 14496-3 §1.6.5.1.
 */
@Suppress("MagicNumber")
internal fun decodeAudioSpecificConfig(asc: ByteArray): AscInfo {
    val r = BitReader(asc)
    var aot = r.readBits(5)
    if (aot == AOT_ESCAPE) aot = 32 + r.readBits(6)
    val freqIdx = r.readBits(4)
    val sampleRate =
        if (freqIdx == EXPLICIT_FREQ_INDEX) r.readBits(24) else SAMPLE_RATE_TABLE.getOrNull(freqIdx)
    val chanCfg = r.readBits(4)
    val channels = if (chanCfg in 1..7) (if (chanCfg == 7) 8 else chanCfg) else null
    return AscInfo(audioObjectType = aot, sampleRate = sampleRate, channels = channels)
}
