package com.calypsan.listenup.client.playback

/**
 * Desktop codec capability detector backed by FFmpeg.
 *
 * FFmpeg decodes all common audio formats natively (AAC, MP3, FLAC, Vorbis,
 * Opus, PCM) without relying on system libraries. This detector simply
 * reports all supported codecs — no runtime probing needed.
 */
class FfmpegCapabilityDetector : AudioCapabilityDetector {
    override fun getSupportedCodecs(): List<String> = listOf("aac", "mp3", "vorbis", "flac", "pcm", "opus")
}
