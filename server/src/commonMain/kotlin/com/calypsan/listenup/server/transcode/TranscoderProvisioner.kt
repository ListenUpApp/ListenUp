package com.calypsan.listenup.server.transcode

import com.calypsan.listenup.server.io.readEnv
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.process.MISSING_BINARY_EXIT_CODE
import com.calypsan.listenup.server.process.SPAWN_FAILED_EXIT_CODE
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = loggerFor<TranscoderProvisioner>()

/** Whether this server can transcode, resolved once at boot. */
sealed interface TranscoderStatus {
    /** A working FFmpeg was found at [path], reporting [version]. */
    data class Available(
        val path: String,
        val version: String,
    ) : TranscoderStatus

    /** No usable encoder. [reason] is operator-facing and appears in the admin surface. */
    data class Unavailable(
        val reason: String,
    ) : TranscoderStatus
}

/**
 * Finds FFmpeg at boot: `$LISTENUP_FFMPEG`, then the image's `/usr/local/bin/ffmpeg`, then `PATH`.
 *
 * ⛔ **Unavailable is not an error.** `prepare()` degrades to direct-play and marks the response, so
 * a server without an encoder behaves exactly as it does today — with the difference that clients
 * can now say why a format may not play, instead of failing silently.
 *
 * @param candidatePaths ordered probe list; the default is the documented resolution order.
 * @param probeTimeout how long one check may run before its binary is treated as wedged. Both
 *   checks feed a tenth of a second of silence or nothing at all, so anything near this is a hang.
 * @param runProcess runs a command, feeding each stderr line to the sink and returning the exit
 *   code — i.e. `ProcessRunner::run`.
 */
class TranscoderProvisioner(
    private val candidatePaths: () -> List<String> = ::defaultCandidates,
    private val decoderCandidatePaths: () -> List<String> = ::defaultDecoderCandidates,
    private val probeTimeout: Duration = DEFAULT_PROBE_TIMEOUT,
    private val runProcess: suspend (List<String>, (String) -> Unit) -> Int,
) {
    /**
     * Probes each candidate in order and returns the first that actually encodes AAC.
     *
     * A candidate that cannot be spawned at all is skipped — that is just an absent binary. One that
     * *runs* and still fails is reported instead of skipped: an ffmpeg on the operator's own
     * `$LISTENUP_FFMPEG` that cannot encode is a misconfiguration they should hear about at boot,
     * not a stall at the first segment.
     */
    suspend fun probe(): TranscoderStatus =
        findEncoder().also { status ->
            // Logged here, once, because this is the only place the answer is known at boot: an
            // operator whose transcoding is off should read why in the startup log, not infer it.
            when (status) {
                is TranscoderStatus.Available -> {
                    log.info { "transcoder available: ${status.path} (${status.version})" }
                }

                is TranscoderStatus.Unavailable -> {
                    log.warn { "transcoder unavailable: ${status.reason}" }
                }
            }
        }

    private suspend fun findEncoder(): TranscoderStatus {
        for (path in candidatePaths()) {
            val output = StringBuilder()
            val exit =
                runBounded(aacProbeCommand(path)) { output.appendLine(it) }
                    ?: return TranscoderStatus.Unavailable(
                        "ffmpeg at $path did not finish the AAC check within $probeTimeout — transcoding is off",
                    )
            if (exit == MISSING_BINARY_EXIT_CODE || exit == SPAWN_FAILED_EXIT_CODE) continue
            if (exit != 0) {
                return TranscoderStatus.Unavailable(
                    "ffmpeg at $path cannot encode AAC (exit $exit) — transcoding is off",
                )
            }
            // Exit 0 alone is not proof: anything that ignores its arguments and succeeds — a
            // mis-set $LISTENUP_FFMPEG pointing at /bin/true is the realistic one — would otherwise
            // be reported as a working transcoder and then fail on every segment.
            val version =
                versionFrom(output.toString())
                    ?: return TranscoderStatus.Unavailable(
                        "$path ran but did not identify itself as ffmpeg — transcoding is off",
                    )
            return TranscoderStatus.Available(path, version)
        }
        return TranscoderStatus.Unavailable("no working ffmpeg found — transcoding is off")
    }

    /**
     * Finds the external FDK decoder, or null when there is none.
     *
     * Needed because FFmpeg cannot correctly decode xHE-AAC — it drops what it cannot parse and
     * exits 0 — so those sources are decoded by Fraunhofer FDK through GStreamer instead. A server
     * without it still transcodes everything else; it just refuses xHE rather than mangling it.
     *
     * The probe builds a pipeline containing the element and feeds it nothing (`num-buffers=0`):
     * exit 0 means the element exists, and a missing element exits 1. Cheap, and it needs no second
     * binary in the image — verified against the distroless runtime, where the plugin registry is
     * built on first use.
     */
    suspend fun probeDecoder(): String? {
        for (path in decoderCandidatePaths()) {
            val exit = runBounded(decoderProbeCommand(path)) { }
            if (exit == null) {
                log.warn { "$path did not finish its fdkaacdec check within $probeTimeout — skipping it" }
                continue
            }
            if (exit == MISSING_BINARY_EXIT_CODE || exit == SPAWN_FAILED_EXIT_CODE) continue
            if (exit != 0) {
                log.warn { "$path has no fdkaacdec element (exit $exit) — xHE-AAC cannot be transcoded" }
                continue
            }
            log.info { "external decoder available: $path (fdkaacdec)" }
            return path
        }
        log.info { "no FDK decoder found — xHE-AAC sources will not be transcoded" }
        return null
    }

    /**
     * [runProcess] under [probeTimeout], or null when it ran out. The timeout cancels the call, and
     * [com.calypsan.listenup.server.process.ProcessRunner.run] kills and reaps its child on
     * cancellation, so a wedged binary is not left behind.
     */
    private suspend fun runBounded(
        command: List<String>,
        onStderr: (String) -> Unit,
    ): Int? = withTimeoutOrNull(probeTimeout) { runProcess(command, onStderr) }
}

/** Generous: a healthy check takes well under a second, even on a cold NAS. */
private val DEFAULT_PROBE_TIMEOUT = 30.seconds

/** Instantiates `fdkaacdec` and feeds it nothing; exit 0 proves the element exists. */
internal fun decoderProbeCommand(path: String): List<String> =
    listOf(path, "-q", "fakesrc", "num-buffers=0", "!", "fdkaacdec", "!", "fakesink")

/** `$LISTENUP_GST_LAUNCH`, then the image's own copy, then `PATH` — mirrors [defaultCandidates]. */
internal fun defaultDecoderCandidates(): List<String> =
    listOfNotNull(
        readEnv("LISTENUP_GST_LAUNCH"),
        "/usr/local/bin/gst-launch-1.0",
        "gst-launch-1.0",
    )

/**
 * Asks ffmpeg to encode a tenth of a second of silence to AAC and throw it away.
 *
 * ⛔ **`-version` cannot answer this question, and neither can `-encoders`.** The native AAC encoder
 * is not a configure flag, so a build with a perfectly good one prints no "aac" anywhere in
 * `-version` (measured against ffmpeg 8.1.2). Both of those also write to **stdout**, which
 * [com.calypsan.listenup.server.process.ProcessRunner] discards on purpose — an undrained stdout
 * pipe deadlocks a child past 64KB. So the probe asks for the real thing: the exit code is the
 * answer, and the banner it needs for [TranscoderStatus.Available.version] arrives on stderr.
 *
 * Raw PCM from `/dev/zero` rather than a `lavfi` source, so a stripped static build with no
 * avfilter still passes.
 *
 * ⛔ **Only components the image's FFmpeg is built with.** `server/Dockerfile.native` configures it
 * `--disable-everything` and enables what transcoding needs, so this probe once asked for the
 * `null` muxer — present in every full build it was ever tested with — and failed on every
 * image-based server, switching transcoding off for all of them. It now muxes ADTS, the format each
 * HLS segment is written in, to `pipe:` — stdout, which the process runner discards.
 * `TranscoderMatchesImageFfmpegTest` holds this command and the Dockerfile together.
 */
internal fun aacProbeCommand(path: String): List<String> =
    listOf(
        path,
        "-nostdin",
        "-f",
        "s16le",
        "-ar",
        "44100",
        "-ac",
        "2",
        "-t",
        "0.1",
        "-i",
        "/dev/zero",
        "-c:a",
        "aac",
        "-f",
        "adts",
        "pipe:",
    )

/** ffmpeg's own banner line, or `null` if whatever ran never printed one. */
private fun versionFrom(output: String): String? =
    output
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("ffmpeg version") }

private fun defaultCandidates(): List<String> =
    listOfNotNull(readEnv("LISTENUP_FFMPEG"), "/usr/local/bin/ffmpeg", "ffmpeg")
