package com.calypsan.listenup.client.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

private const val SAMPLE_RATE = 44_100
private const val ALTERNATE_SAMPLE_RATE = 48_000

/** Media3 hands processors direct buffers in native byte order; mirror that exactly. */
private fun directBuffer(byteCount: Int): ByteBuffer = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.nativeOrder())

private fun pcm16BufferOf(samples: ShortArray): ByteBuffer =
    directBuffer(samples.size * Short.SIZE_BYTES).apply {
        samples.forEach { putShort(it) }
        flip()
    }

private fun pcmFloatBufferOf(samples: FloatArray): ByteBuffer =
    directBuffer(samples.size * Float.SIZE_BYTES).apply {
        samples.forEach { putFloat(it) }
        flip()
    }

private fun ByteBuffer.readShorts(): ShortArray = ShortArray(remaining() / Short.SIZE_BYTES) { getShort() }

private fun ByteBuffer.readFloats(): FloatArray = FloatArray(remaining() / Float.SIZE_BYTES) { getFloat() }

/** A pure tone, the simplest signal whose loudness is stable enough to compare across two runs. */
private fun sine16(
    frames: Int,
    sampleRate: Int,
    frequencyHz: Double = 440.0,
    amplitude: Double = 0.5,
) = ShortArray(frames) { frame ->
    (sin(2.0 * PI * frequencyHz * frame / sampleRate) * amplitude * Short.MAX_VALUE).toInt().toShort()
}

/** `configure` only stages a format; `flush` is what makes it current, so tests always do both. */
private fun GainAudioProcessor.configureAndFlush(format: AudioFormat): AudioFormat = configure(format).also { flush(AudioProcessor.StreamMetadata.DEFAULT) }

private fun GainAudioProcessor.process(input: ByteBuffer): ByteBuffer {
    queueInput(input)
    return getOutput()
}

/**
 * Pins [GainAudioProcessor]'s two contracts: the samples it writes downstream carry the gain, and
 * the samples it hands the R128 meter never do.
 *
 * The second one is the subtle one. Normalization is measured so a later session can play the book
 * at the right level; if the meter heard post-gain audio, every boosted session would re-measure a
 * louder book and drive the correction toward zero. The measurement has to describe the *file*, not
 * what the listener chose to do to it — so the meter is fed from the decoder's samples before the
 * multiply, and the "meter measures the input before gain" test below is what holds that line.
 */
class GainAudioProcessorTest :
    FunSpec({

        context("configuration") {
            test("16-bit stereo activates the processor and passes the format through unchanged") {
                val processor = GainAudioProcessor()
                val format = AudioFormat(SAMPLE_RATE, 2, C.ENCODING_PCM_16BIT)

                processor.configure(format) shouldBe format
                processor.isActive shouldBe true
            }

            test("an encoding that is neither 16-bit nor float PCM is rejected") {
                shouldThrow<AudioProcessor.UnhandledAudioFormatException> {
                    GainAudioProcessor().configure(AudioFormat(SAMPLE_RATE, 2, C.ENCODING_PCM_24BIT))
                }
            }

            test("a staged reconfigure leaves the still-active format's frame math intact") {
                val processor = GainAudioProcessor()
                processor.configureAndFlush(AudioFormat(SAMPLE_RATE, 1, C.ENCODING_PCM_16BIT))

                // configure() only stages: the sink drains what is already in flight before
                // flushing the new format in. Anything rebuilt at configure time would be sized
                // for stereo while the frames still arriving are mono.
                processor.configure(AudioFormat(SAMPLE_RATE, 2, C.ENCODING_PCM_16BIT))

                val tone = sine16(frames = SAMPLE_RATE, sampleRate = SAMPLE_RATE)
                val output = processor.process(pcm16BufferOf(tone)).readShorts()

                output.toList() shouldBe tone.toList()
            }
        }

        context("gain application") {
            test("unity gain round-trips 16-bit input bit-exactly") {
                val processor = GainAudioProcessor()
                processor.configureAndFlush(AudioFormat(SAMPLE_RATE, 1, C.ENCODING_PCM_16BIT))
                val samples = shortArrayOf(0, 1, -1, 8000, -8000, 12_345, Short.MAX_VALUE, Short.MIN_VALUE)

                val output = processor.process(pcm16BufferOf(samples)).readShorts()

                output.toList() shouldBe samples.toList()
            }

            test("+6 dB scales 16-bit samples and clamps both rails") {
                val processor = GainAudioProcessor()
                processor.configureAndFlush(AudioFormat(SAMPLE_RATE, 1, C.ENCODING_PCM_16BIT))
                processor.setGainDb(6f)

                val output = processor.process(pcm16BufferOf(shortArrayOf(8000, 30_000, -30_000))).readShorts()

                withClue("8000 * 10^(6/20) ≈ 15962, allowing one LSB of quantization") {
                    output[0].toFloat() shouldBe (15_962f plusOrMinus 1f)
                }
                output[1] shouldBe Short.MAX_VALUE
                // -32767, not Short.MIN_VALUE: VolumeGain.applySample clamps symmetrically to
                // ±1.0 and the write-back scales by 32767, so the negative rail lands one LSB
                // shy of the two's-complement floor. That symmetry is deliberate — see the
                // implementation's PCM_16_PEAK note.
                output[2].toInt() shouldBe -32_767
            }

            test("+6 dB scales float samples and clamps at unity") {
                val processor = GainAudioProcessor()
                processor.configureAndFlush(AudioFormat(SAMPLE_RATE, 1, C.ENCODING_PCM_FLOAT))
                processor.setGainDb(6f)

                val output = processor.process(pcmFloatBufferOf(floatArrayOf(0.25f, 0.9f))).readFloats()

                output[0] shouldBe (0.499f plusOrMinus 0.001f)
                output[1] shouldBe 1.0f
            }
        }

        context("loudness measurement") {
            test("the meter measures the input before gain is applied") {
                val processor = GainAudioProcessor()
                processor.configureAndFlush(AudioFormat(SAMPLE_RATE, 1, C.ENCODING_PCM_16BIT))
                val tone = sine16(frames = SAMPLE_RATE * 2, sampleRate = SAMPLE_RATE)

                processor.process(pcm16BufferOf(tone))
                val atUnity = processor.measuredGainDb().shouldNotBeNull()

                processor.beginBook()
                processor.measuredGainDb().shouldBeNull()
                processor.setGainDb(12f)
                processor.process(pcm16BufferOf(tone))
                val atBoost = processor.measuredGainDb().shouldNotBeNull()

                withClue("the same input measured twice must read the same, whatever the boost") {
                    atBoost shouldBe (atUnity plusOrMinus 0.2f)
                }
            }

            test("reconfiguring at a new sample rate rebuilds the meter") {
                val processor = GainAudioProcessor()
                processor.configureAndFlush(AudioFormat(SAMPLE_RATE, 1, C.ENCODING_PCM_16BIT))
                processor.process(pcm16BufferOf(sine16(frames = SAMPLE_RATE * 2, sampleRate = SAMPLE_RATE)))
                processor.measuredGainDb().shouldNotBeNull()

                processor.configureAndFlush(AudioFormat(ALTERNATE_SAMPLE_RATE, 1, C.ENCODING_PCM_16BIT))

                withClue("K-weighting coefficients are sample-rate-derived, so the old meter cannot be reused") {
                    processor.measuredGainDb().shouldBeNull()
                }

                processor.process(
                    pcm16BufferOf(sine16(frames = ALTERNATE_SAMPLE_RATE * 2, sampleRate = ALTERNATE_SAMPLE_RATE)),
                )
                processor.measuredGainDb().shouldNotBeNull()
            }
        }
    })
