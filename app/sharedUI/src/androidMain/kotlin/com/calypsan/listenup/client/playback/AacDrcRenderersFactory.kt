package com.calypsan.listenup.client.playback

import android.content.Context
import android.media.MediaFormat
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/**
 * `RenderersFactory` that injects AAC DRC keys into the audio renderer's
 * `MediaFormat`. Applies to AAC-LC, HE-AAC, xHE-AAC alike, so audiobooks
 * play at a consistent loudness instead of swinging between whispered and
 * shouted passages.
 *
 * Defaults:
 *  - target reference level: -24 LKFS, encoded as 96 (the MediaCodec
 *    contract uses `value = -4 × target LKFS` on a 0..127 scale, where
 *    0 = +24 LKFS and 127 ≈ -32 LKFS; e.g. 64 = -16 LKFS).
 *  - effect type: 1 (night mode / limited dynamic range).
 */
@UnstableApi
class AacDrcRenderersFactory(
    context: Context,
) : DefaultRenderersFactory(context) {
    private companion object {
        // -24 LKFS encoded as value = -4 × target LKFS.
        const val DRC_TARGET_REFERENCE_LEVEL = 96

        // Night mode: limited dynamic range, well-suited for audiobooks.
        const val DRC_EFFECT_TYPE_NIGHT = 1
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        out.add(
            object : MediaCodecAudioRenderer(
                context,
                mediaCodecSelector,
                enableDecoderFallback,
                eventHandler,
                eventListener,
                audioSink,
            ) {
                // Confirmed signature for Media3 1.10.1:
                // getMediaFormat(Format, String, int, float) — no AudioSink param.
                override fun getMediaFormat(
                    format: androidx.media3.common.Format,
                    codecMimeType: String,
                    codecMaxInputSize: Int,
                    codecOperatingRate: Float,
                ): MediaFormat {
                    val mediaFormat =
                        super.getMediaFormat(
                            format,
                            codecMimeType,
                            codecMaxInputSize,
                            codecOperatingRate,
                        )
                    // -24 LKFS target reference level, encoded as value = -4 × target LKFS.
                    mediaFormat.setInteger(
                        MediaFormat.KEY_AAC_DRC_TARGET_REFERENCE_LEVEL,
                        DRC_TARGET_REFERENCE_LEVEL,
                    )
                    // Night mode (limited dynamic range). 0 = off.
                    mediaFormat.setInteger(MediaFormat.KEY_AAC_DRC_EFFECT_TYPE, DRC_EFFECT_TYPE_NIGHT)
                    return mediaFormat
                }
            },
        )
        // Defer extension renderers (e.g. MIDI) to the parent. ExoPlayer picks the
        // first capable renderer, so the DRC-aware renderer above will always win
        // for standard AAC decoding; the parent's MediaCodecAudioRenderer is a
        // harmless fallback that is never actually selected.
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out,
        )
    }
}
