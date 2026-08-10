package com.calypsan.listenup.client.playback

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * The default renderers, with [GainAudioProcessor] spliced into the audio sink's processor chain.
 *
 * Media3 offers no way to add a processor to an already-built sink, so overriding [buildAudioSink]
 * — the one seam `DefaultRenderersFactory` leaves open — is how a gain stage gets into the decode
 * path at all. Everything else about the sink is deliberately identical to the default
 * construction: same float-output and playback-parameter flags, same context. Only the processors
 * differ.
 *
 * `setAudioProcessors` prepends to Media3's own chain rather than replacing it — the sink still
 * builds its `DefaultAudioProcessorChain`, so silence-skipping and the Sonic speed/pitch stage keep
 * working behind the gain stage. That ordering is also what the measurement depends on: the meter
 * reads the decoder's samples before Sonic time-stretches them, so a book measures the same at 1×
 * and at 2×.
 */
@OptIn(UnstableApi::class)
internal class GainRenderersFactory(
    context: Context,
    private val gainProcessor: GainAudioProcessor,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink =
        DefaultAudioSink
            .Builder(context)
            .setAudioProcessors(arrayOf(gainProcessor))
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
            .build()
}
