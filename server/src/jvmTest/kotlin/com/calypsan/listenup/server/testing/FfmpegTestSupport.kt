package com.calypsan.listenup.server.testing

import java.io.File
import java.io.OutputStream
import java.nio.file.Path
import kotlin.io.path.absolutePathString

/**
 * Shared support for the two specs that drive a **real** encoder: the transcoder's drift gate and
 * the route-level first-play test.
 *
 * Both skip when the host has no FFmpeg, so a developer without one is not blocked; CI installs it
 * and asserts it is there, so the skip cannot quietly turn either gate green.
 */
object FfmpegTestSupport {
    /** Rate every generated source uses — the one a real library is dominated by. */
    const val SAMPLE_RATE = 44_100

    /** Path to `ffmpeg` on this host, or null when there is none. */
    val ffmpeg: String? by lazy { findOnPath("ffmpeg") }

    /** Path to `ffprobe` on this host, or null when there is none. */
    val ffprobe: String? by lazy { findOnPath("ffprobe") }

    /** Whether a real encode can be driven here at all. */
    val isAvailable: Boolean get() = ffmpeg != null && ffprobe != null

    /** Writes [seconds] of 440 Hz tone to [dest] and returns it — cheap, deterministic, real AAC. */
    fun generateSine(
        dest: Path,
        seconds: Int,
    ): Path {
        val binary = requireNotNull(ffmpeg) { "generateSine called with no ffmpeg on PATH" }
        val exit =
            execute(
                listOf(
                    binary,
                    "-nostdin",
                    "-hide_banner",
                    "-loglevel",
                    "error",
                    "-y",
                    "-f",
                    "lavfi",
                    "-i",
                    "sine=frequency=440:duration=$seconds",
                    "-c:a",
                    "aac",
                    "-ar",
                    SAMPLE_RATE.toString(),
                    dest.absolutePathString(),
                ),
            )
        check(exit == 0) { "generating the test source failed with exit $exit" }
        return dest
    }

    /**
     * Number of AAC frames in [segment].
     *
     * ⛔ **Not `ffprobe -show_entries format=duration`.** On a raw ADTS stream that figure is
     * estimated from the bitrate and reads over a hundred milliseconds short. A frame is exactly
     * 1024 samples, so counting frames is the only exact measurement available here.
     */
    fun frameCount(segment: String): Int {
        val binary = requireNotNull(ffprobe) { "frameCount called with no ffprobe on PATH" }
        val counted =
            capture(
                listOf(
                    binary,
                    "-v",
                    "error",
                    "-count_frames",
                    "-select_streams",
                    "a:0",
                    "-show_entries",
                    "stream=nb_read_frames",
                    "-of",
                    "csv=p=0",
                    segment,
                ),
            ).trim()
        // An empty answer means ffprobe found no readable stream at all — the usual cause is a
        // segment served while it was still being written, so say that rather than raising a
        // NumberFormatException that names neither the file nor the reason.
        return counted.toIntOrNull()
            ?: error("ffprobe read no AAC frames from $segment — empty or truncated (it printed \"$counted\")")
    }

    private fun findOnPath(binary: String): String? =
        System
            .getenv("PATH")
            .orEmpty()
            .split(File.pathSeparator)
            .map { File(it, binary) }
            .firstOrNull { it.canExecute() }
            ?.absolutePath

    private fun execute(command: List<String>): Int {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        // Drained to nowhere, or a chatty child blocks on a full pipe.
        process.inputStream.use { it.copyTo(OutputStream.nullOutputStream()) }
        return process.waitFor()
    }

    private fun capture(command: List<String>): String {
        val process = ProcessBuilder(command).start()
        val output = process.inputStream.use { it.readBytes() }.decodeToString()
        process.waitFor()
        return output
    }
}
