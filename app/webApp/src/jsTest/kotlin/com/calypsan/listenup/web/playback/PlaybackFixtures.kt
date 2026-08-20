package com.calypsan.listenup.web.playback

import com.calypsan.listenup.client.playback.AudioSegment
import com.calypsan.listenup.client.playback.PlaybackState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag

// Every literal below lives in a named constant because detekt's MagicNumber excludes
// `**/test/**`, `**/commonTest/**` and `**/jvmTest/**` but not `**/jsTest/**` — a gap left over
// from when the js source sets joined detekt's scope, tracked separately.

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

/** How long a spec will wait for the browser to reach a state before calling it a failure. */
internal const val AWAIT_TIMEOUT_MS = 15_000L

/**
 * A silent mono PCM WAV of [durationMs], handed to the browser as an object URL.
 *
 * Real, decodable audio is the point. The behaviour these specs cover only exists once the element
 * reaches `loadedmetadata` and starts running its clock, which a URL that 404s never does — so a
 * placeholder would make every one of them vacuous. Generating the bytes beats committing a binary
 * fixture: it is a couple of dozen bytes of header and a run of silence.
 *
 * Callers should [URL.revokeObjectURL] when finished.
 */
internal fun silentWavObjectUrl(durationMs: Long): String {
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

/** One segment of generated silence, positioned at [offsetMs] in the book. */
internal fun silentSegment(
    durationMs: Long,
    offsetMs: Long = 0,
): AudioSegment =
    AudioSegment(
        url = silentWavObjectUrl(durationMs),
        hlsUrl = null,
        localPath = null,
        durationMs = durationMs,
        offsetMs = offsetMs,
    )

/**
 * Suspend until the player reports [target], failing the spec if it never does.
 *
 * Waiting on the state rather than sleeping a fixed span is what keeps these specs honest on a
 * loaded CI box: a timeout is a real failure, where a sleep that finished too early would quietly
 * assert against a browser that had not got there yet.
 */
internal suspend fun HtmlAudioPlayer.awaitState(
    target: PlaybackState,
    timeoutMs: Long = AWAIT_TIMEOUT_MS,
) {
    withTimeout(timeoutMs) { state.first { it == target } }
}
