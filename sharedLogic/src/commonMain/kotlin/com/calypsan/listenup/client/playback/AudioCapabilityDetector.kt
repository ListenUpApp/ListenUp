package com.calypsan.listenup.client.playback

/**
 * Detects which audio codecs the device can play.
 *
 * Platform-specific implementations use the native media APIs
 * to query supported decoders.
 */
interface AudioCapabilityDetector {
    /**
     * Get the list of supported audio codecs.
     *
     * Returns codec names normalized to match server expectations:
     * - "aac" - AAC (LC, HE-AAC, HE-AACv2)
     * - "mp3" - MP3
     * - "opus" - Opus
     * - "vorbis" - Vorbis
     * - "flac" - FLAC
     * - "pcm" - PCM/WAV
     * - "ac3" - Dolby Digital (AC-3)
     * - "eac3" - Dolby Digital Plus (E-AC-3)
     * - "dts" - DTS
     * - "truehd" - Dolby TrueHD
     *
     * @return List of supported codec names
     */
    fun getSupportedCodecs(): List<String>

    /**
     * Check if a specific codec is supported.
     *
     * @param codec Codec name to check
     * @return true if the codec is supported
     */
    fun isCodecSupported(codec: String): Boolean = codec in getSupportedCodecs()
}
