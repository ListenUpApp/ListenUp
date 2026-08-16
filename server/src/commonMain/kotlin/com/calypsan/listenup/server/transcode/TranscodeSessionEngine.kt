package com.calypsan.listenup.server.transcode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/** One file being transcoded for one listener. */
data class TranscodeSession(
    val bookId: String,
    val fileId: String,
    val sourcePath: String,
    val sampleRate: Int,
    val durationMs: Long,
    /** Segment index this encoder run started at — FFmpeg numbers its output from here. */
    val startSegment: Int = 0,
)

/** Whether a segment request got an encoder. */
sealed interface SessionAdmission {
    /** An encoder is running and will reach the requested segment. */
    data object Admitted : SessionAdmission

    /** The concurrency gate is full. The caller answers a retryable `TranscoderBusy`. */
    data object Busy : SessionAdmission
}

/**
 * The engine's seam over `ProcessRunner`, so its tests never spawn a real process.
 *
 * [start] returns once the child is **spawned**, not once it has finished — the encoder runs on
 * behind it, and segment requests are what pull it forward. An implementation that only returned
 * at exit would stall every listener until their whole book had been encoded.
 */
interface TranscodeSpawner {
    suspend fun start(command: List<String>)

    fun stop()
}

/**
 * Owns the running FFmpeg processes: one per file being listened to, each chasing its listener.
 *
 * Two rules shape everything here. **Sequential listening must never respawn** — it is the
 * audiobook 99%, and a fresh process per segment would spend more time seeking than encoding. And
 * **the gate admits rather than queues**: over the concurrency cap a caller is told `Busy` at once
 * so it can retry, because a queue on a self-hosted box just converts a busy server into a slow one.
 */
class TranscodeSessionEngine(
    private val ffmpegPath: String,
    private val cache: SegmentCache,
    private val settings: TranscodeSettings,
    private val newSpawner: () -> TranscodeSpawner,
    private val elapsedMillis: () -> Long = monotonicMillis(),
) {
    private val mutex = Mutex()
    private val running = mutableMapOf<String, RunningSession>()

    /**
     * Makes sure an encoder is working towards [fromSegment] of [session].
     *
     * Reuses the process already running when [fromSegment] is ahead of where it started and within
     * [SEGMENT_LOOKAHEAD]; otherwise stops it and respawns seeked to the new point. A *backward*
     * request always respawns — the encoder only moves forward, so it can never catch up to a
     * segment it has already passed.
     */
    suspend fun ensureRunning(
        session: TranscodeSession,
        fromSegment: Int,
    ): SessionAdmission =
        mutex.withLock {
            val key = key(session.bookId, session.fileId)
            val existing = running[key]
            if (existing != null) {
                if (existing.canReach(fromSegment)) {
                    existing.lastRequestedMillis = elapsedMillis()
                    return@withLock SessionAdmission.Admitted
                }
                existing.spawner.stop()
                running.remove(key)
            }
            if (running.size >= settings.maxConcurrentSessions) return@withLock SessionAdmission.Busy

            running[key] = spawn(session, fromSegment)
            SessionAdmission.Admitted
        }

    /** Stops every session whose listener has gone quiet for [IDLE_KILL_MILLIS]. */
    internal suspend fun sweepIdle() {
        mutex.withLock {
            val now = elapsedMillis()
            val idle = running.filterValues { now - it.lastRequestedMillis >= IDLE_KILL_MILLIS }
            for ((key, session) in idle) {
                session.spawner.stop()
                running.remove(key)
            }
        }
    }

    /** Runs [sweepIdle] on a timer for as long as [scope] lives. Started by DI wiring, not by tests. */
    fun startWatchdog(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                delay(IDLE_SWEEP_MILLIS)
                sweepIdle()
            }
        }

    /** Stops every running encoder — server shutdown, or transcoding being switched off. */
    suspend fun stopAll() {
        mutex.withLock {
            running.values.forEach { it.spawner.stop() }
            running.clear()
        }
    }

    private suspend fun spawn(
        session: TranscodeSession,
        fromSegment: Int,
    ): RunningSession {
        val plan = HlsPlaylist.plan(session.durationMs, session.sampleRate, settings.targetSegmentSeconds)
        cache.prepareDir(session.bookId, session.fileId)
        val spawner = newSpawner()
        spawner.start(
            command(
                ffmpegPath = ffmpegPath,
                session = session.copy(startSegment = fromSegment),
                startSeconds = fromSegment * plan.segmentSeconds,
                plan = plan,
                outputPattern = cache.segmentPattern(session.bookId, session.fileId),
                bitrateKbps = settings.bitrateKbps,
            ),
        )
        return RunningSession(fromSegment, spawner, elapsedMillis())
    }

    private class RunningSession(
        val startSegment: Int,
        val spawner: TranscodeSpawner,
        var lastRequestedMillis: Long,
    ) {
        fun canReach(segment: Int): Boolean = segment >= startSegment && segment - startSegment <= SEGMENT_LOOKAHEAD
    }

    internal companion object {
        /**
         * How far ahead of its start a running encoder is trusted to reach before we restart instead.
         *
         * Audio transcodes far faster than realtime, so the ~5 minutes this covers is a couple of
         * seconds of encoding — worth waiting for. A longer jump is cheaper to serve by killing the
         * process and seeking, which costs about a second however far it moves.
         */
        const val SEGMENT_LOOKAHEAD = 30

        /** Silence after which a listener is assumed gone and their encoder is stopped. */
        const val IDLE_KILL_MILLIS = 60_000L

        /** How often [startWatchdog] looks for idle sessions. */
        const val IDLE_SWEEP_MILLIS = 10_000L

        fun key(
            bookId: String,
            fileId: String,
        ): String = "$bookId/$fileId"
    }
}

/** Wall-clock-independent millisecond reading, so the engine's idle logic is testable. */
private fun monotonicMillis(): () -> Long {
    val origin = TimeSource.Monotonic.markNow()
    return { origin.elapsedNow().inWholeMilliseconds }
}

/**
 * The FFmpeg invocation for one encoder run.
 *
 * Kept as a pure function so the engine's tests can assert on the exact argv without a process.
 */
private fun command(
    ffmpegPath: String,
    session: TranscodeSession,
    startSeconds: Double,
    plan: HlsPlaylist.Plan,
    outputPattern: String,
    bitrateKbps: Int,
): List<String> =
    listOf(
        ffmpegPath,
        "-nostdin",
        "-hide_banner",
        "-loglevel",
        "warning",
        // -ss BEFORE -i seeks by index rather than decoding to the point: the difference between
        // starting a 90-hour book's last chapter in a second and in a minute.
        "-ss",
        HlsPlaylist.formatSeconds(startSeconds),
        "-i",
        session.sourcePath,
        "-vn",
        "-map",
        "0:a:0",
        "-c:a",
        "aac",
        "-b:a",
        "${bitrateKbps}k",
        // ⛔ Never resample: HlsPlaylist's frame math is computed from the SOURCE rate, and an
        // output at a different rate makes every declared EXTINF describe a file we did not write.
        "-ar",
        session.sampleRate.toString(),
        "-f",
        "segment",
        "-segment_format",
        "adts",
        "-segment_time",
        HlsPlaylist.formatSeconds(plan.segmentSeconds),
        "-segment_start_number",
        session.startSegment.toString(),
        outputPattern,
    )
