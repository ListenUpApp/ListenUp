@file:Suppress("MagicNumber", "TooManyFunctions")

package com.calypsan.listenup.client.playback

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.Frame
import java.nio.ShortBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine

private val logger = KotlinLogging.logger {}

/**
 * Desktop audio player using FFmpeg for decoding and javax.sound for output.
 *
 * Decodes any audio format (M4B/AAC, MP3, FLAC, OGG, Opus, WAV) via FFmpeg's
 * bundled native libraries — no system dependencies, no version matching.
 * Audio output uses javax.sound.sampled (SourceDataLine), which works on all
 * platforms via PipeWire/ALSA (Linux), CoreAudio (macOS), DirectSound (Windows).
 *
 * Speed control uses FFmpeg's atempo filter for pitch-preserving time-stretch.
 *
 * HTTP auth headers are set directly on FFmpeg's HTTP protocol, eliminating
 * the need for a local proxy server.
 */
class FfmpegAudioPlayer(
    private val tokenProvider: AudioTokenProvider,
    private val scope: CoroutineScope,
) : AudioPlayer {
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    override val state: StateFlow<PlaybackState> = _state

    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs

    private var grabber: FFmpegFrameGrabber? = null
    private var filter: FFmpegFrameFilter? = null
    private var audioLine: SourceDataLine? = null
    private var decodeJob: Job? = null

    private var segments: List<AudioSegment> = emptyList()
    private var currentSegmentIndex: Int = 0
    private var currentSpeed: Float = 1.0f
    private var hasStartedPlaying: Boolean = false
    private var pendingSeekMs: Long? = null

    private var sampleRate: Int = 0
    private var channels: Int = 0

    override suspend fun load(segments: List<AudioSegment>) {
        if (segments.isEmpty()) {
            logger.error { "Cannot load empty segment list" }
            _state.value = PlaybackState.Error(message = "Playback error. No audio segments were provided.")
            return
        }

        this.segments = segments
        currentSegmentIndex = 0
        hasStartedPlaying = false
        pendingSeekMs = null
        _durationMs.value = segments.sumOf { it.durationMs }
        _state.value = PlaybackState.Buffering

        logger.info { "Loaded ${segments.size} segments, total duration: ${_durationMs.value}ms" }
    }

    override fun play() {
        if (hasStartedPlaying && _state.value == PlaybackState.Paused) {
            resumePlayback()
        } else {
            startSegment(currentSegmentIndex)
        }
    }

    override fun pause() {
        stopDecodeLoop()
        audioLine?.stop()
        audioLine?.flush()
        // Sync grabber to the last reported position (discard decoded-but-unplayed audio)
        val segment = segments.getOrNull(currentSegmentIndex)
        if (segment != null && grabber != null) {
            val segmentOffset = _positionMs.value - segment.offsetMs
            grabber?.timestamp = segmentOffset * 1000
        }
        _state.value = PlaybackState.Paused
    }

    override fun seekTo(positionMs: Long) {
        val coercedPosition = positionMs.coerceIn(0, _durationMs.value)
        val (segmentIndex, segmentOffset) = resolvePosition(coercedPosition)

        if (!hasStartedPlaying) {
            currentSegmentIndex = segmentIndex
            pendingSeekMs = coercedPosition
            _positionMs.value = coercedPosition
            return
        }

        val wasPlaying = _state.value == PlaybackState.Playing
        stopDecodeLoop()
        audioLine?.flush()

        if (segmentIndex != currentSegmentIndex) {
            closeCurrentSegment()
            currentSegmentIndex = segmentIndex
            openSegment(segmentIndex)
        }

        // Seek within the current segment (microseconds)
        grabber?.timestamp = segmentOffset * 1000
        _positionMs.value = coercedPosition

        if (wasPlaying) {
            startDecodeLoop()
        }
    }

    override fun setSpeed(speed: Float) {
        currentSpeed = speed
        if (hasStartedPlaying && _state.value == PlaybackState.Playing) {
            val wasPlaying = true
            stopDecodeLoop()
            rebuildFilter(speed)
            if (wasPlaying) {
                startDecodeLoop()
            }
        } else {
            rebuildFilter(speed)
        }
    }

    override fun release() {
        stopDecodeLoop()
        closeCurrentSegment()
        segments = emptyList()
        currentSegmentIndex = 0
        hasStartedPlaying = false
        pendingSeekMs = null
        currentSpeed = 1.0f
        _state.value = PlaybackState.Idle
        _positionMs.value = 0L
        _durationMs.value = 0L
        logger.info { "FfmpegAudioPlayer released" }
    }

    /**
     * Open a segment for playback: create FFmpegFrameGrabber, open audio line.
     */
    private fun openSegment(index: Int) {
        val segment = segments.getOrNull(index)
        if (segment == null) {
            logger.error { "Invalid segment index: $index" }
            _state.value = PlaybackState.Error(message = "Playback error. Segment index $index is out of range.")
            return
        }

        try {
            val source = segment.localPath ?: segment.url

            logger.info { "Opening segment $index: ${source.takeLast(60)}" }

            val newGrabber = FFmpegFrameGrabber(source)

            // Force interleaved S16 output — matches SourceDataLine's PCM_SIGNED 16-bit format.
            // Without this, decoders output various formats (FLTP for AAC/MP3) requiring
            // manual conversion that's error-prone. Let FFmpeg's swr handle it.
            newGrabber.sampleFormat = avutil.AV_SAMPLE_FMT_S16

            // Set HTTP auth headers for remote URLs
            if (segment.localPath == null) {
                val token = tokenProvider.getToken()
                if (token != null) {
                    newGrabber.setOption("headers", "Authorization: Bearer $token\r\n")
                }
                newGrabber.setOption("timeout", "10000000") // 10s timeout in microseconds
                newGrabber.setOption("reconnect", "1")
            }

            newGrabber.start()

            sampleRate = newGrabber.sampleRate
            channels = newGrabber.audioChannels

            if (sampleRate <= 0 || channels <= 0) {
                logger.error { "Invalid audio format: sampleRate=$sampleRate, channels=$channels" }
                newGrabber.stop()
                newGrabber.release()
                _state.value =
                    PlaybackState.Error(
                        message = "Playback error. Audio stream has invalid format (${sampleRate}Hz, ${channels}ch).",
                    )
                return
            }

            logger.info {
                "Audio format: ${sampleRate}Hz, ${channels}ch, " +
                    "duration=${newGrabber.lengthInTime / 1000}ms"
            }

            // Open audio output line
            val format =
                AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sampleRate.toFloat(),
                    16, // bits per sample
                    channels,
                    channels * 2, // frame size in bytes
                    sampleRate.toFloat(),
                    false, // little-endian
                )
            val lineInfo = DataLine.Info(SourceDataLine::class.java, format)
            val line = AudioSystem.getLine(lineInfo) as SourceDataLine
            line.open(format, sampleRate * channels * 2 / 10) // ~100ms buffer
            line.start()

            grabber = newGrabber
            audioLine = line

            // Build speed filter if needed
            if (currentSpeed != 1.0f) {
                rebuildFilter(currentSpeed)
            }

            // Apply pending seek
            val seek = pendingSeekMs
            if (seek != null) {
                pendingSeekMs = null
                val (_, segmentOffset) = resolvePosition(seek)
                newGrabber.timestamp = segmentOffset * 1000
                logger.debug { "Applied pending seek: ${segmentOffset}ms into segment" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Failed to open segment $index" }
            _state.value = PlaybackState.Error(message = "Playback failed: ${e.message}")
        }
    }

    /**
     * Close current segment resources (grabber, filter, audio line).
     */
    private fun closeCurrentSegment() {
        try {
            filter?.stop()
            filter?.release()
        } catch (e: Exception) {
            logger.debug(e) { "Error closing filter" }
        }
        filter = null

        try {
            audioLine?.stop()
            audioLine?.flush()
            audioLine?.close()
        } catch (e: Exception) {
            logger.debug(e) { "Error closing audio line" }
        }
        audioLine = null

        try {
            grabber?.stop()
            grabber?.release()
        } catch (e: Exception) {
            logger.debug(e) { "Error closing grabber" }
        }
        grabber = null
    }

    /**
     * Start playing a segment from the beginning (or pending seek position).
     */
    private fun startSegment(index: Int) {
        closeCurrentSegment()
        currentSegmentIndex = index
        openSegment(index)

        if (_state.value is PlaybackState.Error) return

        hasStartedPlaying = true
        _state.value = PlaybackState.Playing
        startDecodeLoop()
    }

    /**
     * Resume playback after pause (audio line already exists).
     */
    private fun resumePlayback() {
        audioLine?.start()
        _state.value = PlaybackState.Playing
        startDecodeLoop()
    }

    /**
     * Start the background decode loop.
     * Reads frames from FFmpeg, applies speed filter, writes PCM to audio output.
     */
    private fun startDecodeLoop() {
        stopDecodeLoop()
        decodeJob =
            scope.launch(Dispatchers.IO) {
                try {
                    this.decodeLoop()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isActive) {
                        logger.error(e) { "Decode loop error" }
                        _state.value = PlaybackState.Error(message = "Playback failed: ${e.message}")
                    }
                }
            }
    }

    private fun CoroutineScope.decodeLoop() {
        val currentGrabber = grabber ?: return
        val line = audioLine ?: return
        val segment = segments.getOrNull(currentSegmentIndex) ?: return

        while (isActive) {
            val frame = currentGrabber.grabSamples() ?: break

            if (frame.samples == null || frame.samples.isEmpty()) continue

            // Apply speed filter if active
            val outputFrame =
                if (filter != null) {
                    filter!!.push(frame)
                    filter!!.pullSamples() ?: continue
                } else {
                    frame
                }

            // Convert frame samples to PCM bytes
            val bytes = frameToPcmBytes(outputFrame) ?: continue

            // Write to audio output (blocks until buffer has space)
            line.write(bytes, 0, bytes.size)

            // Update book-relative position
            val positionInSegmentMs = currentGrabber.timestamp / 1000
            val bookPosition = segment.offsetMs + positionInSegmentMs
            _positionMs.value = bookPosition.coerceAtMost(_durationMs.value)
        }

        // End of segment reached naturally
        if (isActive) {
            onSegmentFinished()
        }
    }

    private fun stopDecodeLoop() {
        decodeJob?.cancel()
        decodeJob = null
    }

    /**
     * Called when the current segment finishes naturally.
     * Advances to next segment or signals playback complete.
     */
    private fun onSegmentFinished() {
        val nextIndex = currentSegmentIndex + 1
        if (nextIndex < segments.size) {
            logger.info { "Advancing to segment $nextIndex/${segments.size}" }
            startSegment(nextIndex)
        } else {
            logger.info { "All segments finished" }
            _positionMs.value = _durationMs.value
            _state.value = PlaybackState.Ended
            audioLine?.drain()
        }
    }

    /**
     * Build or rebuild the atempo filter for the given speed.
     * The atempo filter supports [0.5, 100.0] per instance.
     * For values outside [0.5, 2.0], chain multiple instances for quality.
     */
    private fun rebuildFilter(speed: Float) {
        try {
            filter?.stop()
            filter?.release()
        } catch (e: Exception) {
            logger.debug(e) { "Error releasing old filter" }
        }
        filter = null

        if (speed == 1.0f || sampleRate <= 0 || channels <= 0) return

        try {
            val filterString = buildAtempoChain(speed)
            logger.debug { "Speed filter: $filterString" }
            val newFilter = FFmpegFrameFilter(filterString, channels)
            newFilter.sampleRate = sampleRate
            newFilter.start()
            filter = newFilter
        } catch (e: Exception) {
            logger.error(e) { "Failed to create speed filter, playing at 1.0x" }
        }
    }

    /**
     * Build an atempo filter chain string.
     * Each atempo instance works best in [0.5, 2.0] range.
     * Chain multiple for higher/lower speeds.
     */
    private fun buildAtempoChain(speed: Float): String {
        val filters = mutableListOf<String>()
        var remaining = speed.toDouble()
        while (remaining > 2.0) {
            filters.add("atempo=2.0")
            remaining /= 2.0
        }
        while (remaining < 0.5) {
            filters.add("atempo=0.5")
            remaining /= 0.5
        }
        filters.add("atempo=$remaining")
        return filters.joinToString(",")
    }

    /**
     * Convert an FFmpeg Frame's audio samples to bytes for SourceDataLine.
     *
     * Since we set sampleFormat = AV_SAMPLE_FMT_S16, FFmpeg always outputs
     * interleaved 16-bit signed PCM in native byte order. We just need to
     * extract the bytes from the ShortBuffer in little-endian order.
     */
    private fun frameToPcmBytes(frame: Frame): ByteArray? {
        val samples = frame.samples ?: return null
        if (samples.isEmpty()) return null

        val buffer = samples[0] as? ShortBuffer ?: return null
        buffer.rewind()
        val shorts = ShortArray(buffer.remaining())
        buffer.get(shorts)
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            val s = shorts[i].toInt()
            bytes[i * 2] = (s and 0xFF).toByte()
            bytes[i * 2 + 1] = (s shr 8 and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * Translate a book-relative position to segment index + offset within segment.
     */
    private fun resolvePosition(bookPositionMs: Long): Pair<Int, Long> {
        var accumulated = 0L
        for ((index, segment) in segments.withIndex()) {
            if (bookPositionMs < accumulated + segment.durationMs) {
                return index to (bookPositionMs - accumulated)
            }
            accumulated += segment.durationMs
        }
        return if (segments.isNotEmpty()) {
            segments.lastIndex to segments.last().durationMs
        } else {
            0 to 0L
        }
    }
}
