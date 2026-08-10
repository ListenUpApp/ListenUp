package com.calypsan.listenup.client.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.calypsan.listenup.client.playback.loudness.LoudnessMeter
import com.calypsan.listenup.client.playback.loudness.VolumeGain
import java.nio.ByteBuffer

/**
 * The Android gain stage: multiplies decoded PCM by a linear gain on its way to the audio sink,
 * and feeds the *pre-gain* samples to the shared EBU R128 meter.
 *
 * ## Why the meter hears the audio before the multiply
 *
 * The measurement exists to describe the **file**, so a later session can open the same book at a
 * sane level. If the meter heard post-gain audio, every boosted session would measure a louder
 * book, hand back a smaller correction, and the normalization would walk itself to zero over a few
 * listens. Feeding [LoudnessMeter] from the decoder's samples — before [VolumeGain.applySample] —
 * makes that drift unrepresentable rather than merely unlikely.
 *
 * ## Threading
 *
 * [queueInput] and [onFlush] both run on the playback thread, which is why [meterBatch] and the
 * `inputAudioFormat` reads need no synchronization — exactly the confinement
 * [BaseAudioProcessor]'s own `inputAudioFormat` field relies on. The two members that *are*
 * reachable from elsewhere are guarded: [linearGain] is `@Volatile` because [setGainDb] is called
 * from the main thread, and [meter] sits behind [meterLock] because [beginBook] and
 * [measuredGainDb] are called from the UI/ViewModel side while audio is flowing. The lock is taken
 * once per batch flush — never per sample — so the hot loop stays a plain array write.
 *
 * ## The rebuilt-meter tradeoff
 *
 * K-weighting coefficients are derived from the sample rate, so a meter cannot outlive a format
 * change; [onFlush] builds a fresh one. A mid-book format change therefore restarts the
 * measurement, losing whatever coverage the book had accumulated. That is rare (it takes a
 * sample-rate switch between files of one book) and self-correcting on the next listen, which is a
 * better trade than reporting a loudness computed with the wrong filter.
 */
@OptIn(UnstableApi::class)
internal class GainAudioProcessor : BaseAudioProcessor() {
    /** Read on the playback thread, written from the main thread by [setGainDb]. */
    @Volatile private var linearGain: Float = UNITY_GAIN

    private val meterLock = Any()

    /** Guarded by [meterLock]; null until the first [onFlush]. */
    private var meter: LoudnessMeter? = null

    /**
     * Reused pre-gain scratch, sized in whole frames of the active format so the per-sample path
     * never allocates and a batch always lands on a frame boundary. Playback-thread confined.
     */
    private var meterBatch: FloatArray = FloatArray(0)

    /** Set the combined normalization + boost gain. Takes effect on the next queued buffer. */
    fun setGainDb(db: Float) {
        linearGain = VolumeGain.dbToLinear(db)
    }

    /** Start a fresh measurement. Loudness integrates over one book, never across two. */
    fun beginBook() {
        synchronized(meterLock) { meter?.reset() }
    }

    /** R128 normalization gain for everything measured so far, or null before the meter can gate. */
    fun measuredGainDb(): Float? = synchronized(meterLock) { meter?.normalizationGainDb() }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(
                "Volume boost needs 16-bit or float PCM.",
                inputAudioFormat,
            )
        }
        return inputAudioFormat
    }

    /**
     * Build the meter and its scratch against the format that is now *active*.
     *
     * `configure` only stages a format — [BaseAudioProcessor] promotes it into `inputAudioFormat`
     * here, at flush. Anything built at configure time would therefore be sized for the incoming
     * format while [queueInput] is still doing frame math with the outgoing one, on whatever the
     * sink drains in between. Mono → stereo makes that a read past the end of the batch.
     */
    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        // An unconfigured processor still gets flushed, and `AudioFormat.NOT_SET` carries a
        // channel count of -1 — which a meter cannot be built from.
        val channelCount = inputAudioFormat.channelCount
        if (channelCount <= 0) return
        meterBatch = FloatArray((METER_BATCH_SAMPLES / channelCount).coerceAtLeast(1) * channelCount)
        synchronized(meterLock) {
            meter = LoudnessMeter(inputAudioFormat.sampleRate, channelCount)
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        // One volatile read per buffer: a gain change lands on a buffer boundary rather than
        // zippering through the middle of one.
        val gain = linearGain
        val isUnity = gain == UNITY_GAIN
        val is16Bit = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        val channelCount = inputAudioFormat.channelCount
        val output = replaceOutputBuffer(inputBuffer.remaining())
        val batch = meterBatch
        var batchFill = 0

        while (inputBuffer.hasRemaining()) {
            val sample: Float
            if (is16Bit) {
                // Unity writes the original short back verbatim: routing it through float and
                // re-quantizing would cost up to an LSB per sample for no gain at all.
                val raw = inputBuffer.getShort()
                sample = raw / PCM_16_FULL_SCALE
                output.putShort(if (isUnity) raw else VolumeGain.applySample(sample, gain).toPcm16())
            } else {
                sample = inputBuffer.getFloat()
                output.putFloat(if (isUnity) sample else VolumeGain.applySample(sample, gain))
            }
            batch[batchFill++] = sample
            if (batchFill == batch.size) {
                feedMeter(batch, batchFill / channelCount)
                batchFill = 0
            }
        }
        if (batchFill > 0) feedMeter(batch, batchFill / channelCount)
        output.flip()
    }

    private fun feedMeter(
        batch: FloatArray,
        frameCount: Int,
    ) {
        synchronized(meterLock) { meter?.addFrames(batch, frameCount) }
    }

    /**
     * Quantize a clamped ±1.0 sample back to 16-bit. Scaling by 32767 rather than 32768 keeps the
     * rails symmetric with [VolumeGain.applySample]'s own ±1.0 clamp, so a hard-clipped negative
     * peak lands on -32767 and not the two's-complement floor — a 0.0003 dB asymmetry against the
     * decoder's -32768, in exchange for one clamp definition shared by both platforms.
     */
    private fun Float.toPcm16(): Short = (this * PCM_16_PEAK).toInt().toShort()

    private companion object {
        /** No multiply needed at exactly this gain — the fast path writes samples through. */
        const val UNITY_GAIN = 1f

        /** Short→float divisor: 32768 maps `Short.MIN_VALUE` to exactly -1.0. */
        const val PCM_16_FULL_SCALE = 32_768f

        /** Float→short multiplier: 32767 maps ±1.0 symmetrically onto `Short.MAX_VALUE`. */
        const val PCM_16_PEAK = 32_767f

        /**
         * Target scratch size in samples — roughly 46 ms of stereo 44.1 kHz, so the meter is fed
         * a handful of times per queued buffer and the lock is taken about as often.
         */
        const val METER_BATCH_SAMPLES = 4096
    }
}
