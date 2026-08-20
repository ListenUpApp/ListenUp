package com.calypsan.listenup.web.playback

import com.calypsan.listenup.client.playback.AudioSegment
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeBetween
import kotlinx.coroutines.delay
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

// --- The WAV fixture -------------------------------------------------------------------------
// Every literal lives in a named constant because detekt's MagicNumber excludes `**/test/**`,
// `**/commonTest/**` and `**/jvmTest/**` but not `**/jsTest/**` — a gap left over from when the
// js source sets joined detekt's scope.

private const val SAMPLE_RATE_HZ = 8_000
private const val BITS_PER_SAMPLE = 8
private const val BITS_PER_BYTE = 8
private const val BYTE_MASK = 0xFF
private const val MONO_CHANNEL_COUNT = 1
private const val PCM_FORMAT_TAG = 1
private const val WORD_BYTES = 4
private const val HALF_WORD_BYTES = 2
private const val FMT_CHUNK_BYTES = 16

/** The RIFF chunk minus the audio itself: the `WAVE` tag, the fmt chunk, the data chunk header. */
private const val RIFF_OVERHEAD_BYTES = 36

/** Mid-scale is silence for unsigned 8-bit PCM. */
private const val SILENT_8_BIT_SAMPLE = 0x80

private const val MILLIS_PER_SECOND = 1_000

/**
 * A silent mono PCM WAV of [durationMs], handed to the browser as an object URL.
 *
 * Real, decodable audio is the point: the regression under test only appears once the element
 * reaches `loadedmetadata`, which a URL that 404s never does. Generating it beats committing a
 * binary fixture — it is a couple of dozen bytes of header and a run of silence.
 */
private fun silentWavObjectUrl(durationMs: Long): String {
    val dataBytes = (SAMPLE_RATE_HZ * durationMs / MILLIS_PER_SECOND).toInt()
    val bytesPerFrame = MONO_CHANNEL_COUNT * BITS_PER_SAMPLE / BITS_PER_BYTE
    val header = mutableListOf<Byte>()

    fun ascii(text: String) = text.forEach { header += it.code.toByte() }

    fun littleEndian(
        value: Int,
        byteCount: Int,
    ) = repeat(byteCount) { i -> header += ((value shr i * BITS_PER_BYTE) and BYTE_MASK).toByte() }

    ascii("RIFF")
    littleEndian(RIFF_OVERHEAD_BYTES + dataBytes, WORD_BYTES)
    ascii("WAVE")
    ascii("fmt ")
    littleEndian(FMT_CHUNK_BYTES, WORD_BYTES)
    littleEndian(PCM_FORMAT_TAG, HALF_WORD_BYTES)
    littleEndian(MONO_CHANNEL_COUNT, HALF_WORD_BYTES)
    littleEndian(SAMPLE_RATE_HZ, WORD_BYTES)
    littleEndian(SAMPLE_RATE_HZ * bytesPerFrame, WORD_BYTES)
    littleEndian(bytesPerFrame, HALF_WORD_BYTES)
    littleEndian(BITS_PER_SAMPLE, HALF_WORD_BYTES)
    ascii("data")
    littleEndian(dataBytes, WORD_BYTES)

    val wav =
        ByteArray(header.size + dataBytes) { i ->
            if (i < header.size) header[i] else SILENT_8_BIT_SAMPLE.toByte()
        }
    return URL.createObjectURL(Blob(arrayOf(wav), BlobPropertyBag(type = "audio/wav")))
}

// --- The guard -------------------------------------------------------------------------------

private const val SEGMENT_DURATION_MS = 1_500L
private const val RESUME_MS = 750L

/** A seek lands on a sample boundary, not a millisecond one; ±100 ms is far below the 750 ms gap. */
private const val TOLERANCE_MS = 100L

private const val SAMPLE_COUNT = 30
private const val SAMPLE_INTERVAL_MS = 50L

/**
 * Losing a listener's place is the one failure this app cannot afford, and `load()` followed by
 * `seekTo()` — what `PlaybackManagerImpl` does on every resume — is the path it travels.
 *
 * For a single-file `.m4b`, which is most audiobooks, that seek stays inside segment 0. A version
 * of this player recorded the resume offset only on attach, so the same-segment branch left the
 * recorded value at zero and `loadedmetadata` rewound the book to the beginning — after
 * `positionMs` had already reported the correct place, which is the worst kind of wrong.
 */
class HtmlAudioPlayerResumeTest :
    FunSpec({

        test("a resume inside the first segment survives the element becoming ready") {
            val url = silentWavObjectUrl(SEGMENT_DURATION_MS)
            val player = HtmlAudioPlayer()
            player.load(
                listOf(
                    AudioSegment(
                        url = url,
                        hlsUrl = null,
                        localPath = null,
                        durationMs = SEGMENT_DURATION_MS,
                        offsetMs = 0,
                    ),
                ),
            )

            player.seekTo(RESUME_MS)

            // `loadedmetadata` arrives asynchronously and is what used to undo the seek, so watch
            // across that window rather than sampling once and calling it settled.
            repeat(SAMPLE_COUNT) {
                delay(SAMPLE_INTERVAL_MS)
                player.positionMs.value.shouldBeBetween(RESUME_MS - TOLERANCE_MS, RESUME_MS + TOLERANCE_MS)
            }

            player.releasePlayer()
            URL.revokeObjectURL(url)
        }
    })
