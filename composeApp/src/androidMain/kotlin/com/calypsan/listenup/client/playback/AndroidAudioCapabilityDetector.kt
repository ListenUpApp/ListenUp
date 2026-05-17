@file:Suppress("StringLiteralDuplication", "NestedBlockDepth")

package com.calypsan.listenup.client.playback

import android.media.MediaCodecList
import android.media.MediaFormat
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Android implementation of AudioCapabilityDetector.
 *
 * Uses MediaCodecList to query available audio decoders.
 * Results are cached since codec availability doesn't change at runtime.
 */
class AndroidAudioCapabilityDetector : AudioCapabilityDetector {
    private val cachedCodecs: List<String> by lazy { detectSupportedCodecs() }

    override fun getSupportedCodecs(): List<String> = cachedCodecs

    private fun detectSupportedCodecs(): List<String> {
        val codecs = mutableSetOf<String>()

        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)

            for (codecInfo in codecList.codecInfos) {
                // Skip encoders - we only need decoders
                if (codecInfo.isEncoder) continue

                for (mimeType in codecInfo.supportedTypes) {
                    // Map MIME types to normalized codec names
                    val codec = mimeTypeToCodec(mimeType)
                    if (codec != null) {
                        codecs.add(codec)
                    }
                }
            }

            logger.info { "Detected supported audio codecs: $codecs" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to detect codecs, using fallback" }
            // Fallback to commonly supported codecs
            return listOf("aac", "mp3", "opus", "vorbis", "flac", "pcm")
        }

        // If detection found nothing, use fallback
        if (codecs.isEmpty()) {
            logger.warn { "No codecs detected, using fallback" }
            return listOf("aac", "mp3", "opus", "vorbis", "flac", "pcm")
        }

        return codecs.toList()
    }

    /**
     * Map Android MIME types to normalized codec names.
     *
     * MIME type reference:
     * https://developer.android.com/reference/android/media/MediaFormat
     *
     * Visible for testing.
     */
    internal fun mimeTypeToCodec(mimeType: String): String? =
        when (mimeType.lowercase()) {
            // AAC variants
            MediaFormat.MIMETYPE_AUDIO_AAC,
            "audio/mp4a-latm",
            -> {
                "aac"
            }

            // MP3
            MediaFormat.MIMETYPE_AUDIO_MPEG,
            "audio/mp3",
            -> {
                "mp3"
            }

            // Opus
            MediaFormat.MIMETYPE_AUDIO_OPUS -> {
                "opus"
            }

            // Vorbis
            MediaFormat.MIMETYPE_AUDIO_VORBIS -> {
                "vorbis"
            }

            // FLAC
            MediaFormat.MIMETYPE_AUDIO_FLAC -> {
                "flac"
            }

            // PCM/RAW
            MediaFormat.MIMETYPE_AUDIO_RAW -> {
                "pcm"
            }

            // Dolby Digital (AC-3)
            MediaFormat.MIMETYPE_AUDIO_AC3 -> {
                "ac3"
            }

            // Dolby Digital Plus (E-AC-3)
            MediaFormat.MIMETYPE_AUDIO_EAC3 -> {
                "eac3"
            }

            // DTS
            "audio/vnd.dts",
            "audio/vnd.dts.hd",
            -> {
                "dts"
            }

            // Dolby TrueHD
            MediaFormat.MIMETYPE_AUDIO_DOLBY_TRUEHD -> {
                "truehd"
            }

            // AMR (narrow/wideband) - less common but might be useful
            MediaFormat.MIMETYPE_AUDIO_AMR_NB,
            MediaFormat.MIMETYPE_AUDIO_AMR_WB,
            -> {
                "amr"
            }

            // GSM - very old, unlikely to be used
            MediaFormat.MIMETYPE_AUDIO_MSGSM,
            MediaFormat.MIMETYPE_AUDIO_G711_ALAW,
            MediaFormat.MIMETYPE_AUDIO_G711_MLAW,
            -> {
                null
            }

            // Don't report these

            // Unknown - don't report
            else -> {
                logger.debug { "Unknown audio MIME type: $mimeType" }
                null
            }
        }
}
