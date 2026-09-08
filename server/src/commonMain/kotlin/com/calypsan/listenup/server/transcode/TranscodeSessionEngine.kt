package com.calypsan.listenup.server.transcode

import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

private val log = loggerFor<TranscodeSessionEngine>()

/** One file being transcoded for one listener. */
data class TranscodeSession(
    val bookId: String,
    val fileId: String,
    val sourcePath: String,
    val sampleRate: Int,
    val durationMs: Long,
    /** Segment index this encoder run started at — FFmpeg numbers its output from here. */
    val startSegment: Int = 0,
    /** Container codec (`book_audio_files.codec`), e.g. `aac`, `mp3` — picks the decoder path. */
    val codec: String = "aac",
    /** AAC object type (`codecProfile`), e.g. `lc`, `xhe`; null when unknown. */
    val codecProfile: String? = null,
    /** Channel count. Raw PCM between pipeline stages carries no header, so this must be right. */
    val channels: Int = TranscodeCommand.FALLBACK_CHANNELS,
)

/** Whether a segment request got an encoder. */
sealed interface SessionAdmission {
    /** An encoder is running and will reach the requested segment. */
    data object Admitted : SessionAdmission

    /** The concurrency gate is full. The caller answers a retryable `TranscoderBusy`. */
    data object Busy : SessionAdmission

    /**
     * This source needs a decoder the server does not have, so no encoder was started.
     *
     * Refusing beats encoding: FFmpeg's own xHE-AAC decoder drops roughly a quarter of the audio
     * and still exits 0, so "transcode it anyway" would hand the listener a book with a fifth of it
     * silently missing and a seek bar that lies about it.
     */
    data object Unsupported : SessionAdmission
}

/**
 * The engine's seam over `ProcessRunner`, so its tests never spawn a real process.
 *
 * [start] returns once the child is **spawned**, not once it has finished — the encoder runs on
 * behind it, and segment requests are what pull it forward. An implementation that only returned
 * at exit would stall every listener until their whole book had been encoded.
 */
interface TranscodeSpawner {
    suspend fun start(commands: List<List<String>>)

    fun stop()
}

/**
 * Owns the running FFmpeg processes: one per file being listened to, each chasing its listener.
 *
 * Two rules shape everything here. **Sequential listening must never respawn** — it is the
 * audiobook 99%, and a fresh process per segment would spend more time seeking than encoding. And
 * **the gate admits rather than queues**: over the concurrency cap a caller is told `Busy` at once
 * so it can retry, because a queue on a self-hosted box just converts a busy server into a slow one.
 *
 * [ffmpegPath] is a supplier rather than a value because the boot probe that finds FFmpeg is
 * asynchronous while this object's construction is not: resolving the path per spawn means a probe
 * that finishes after the engine was built is still honoured.
 */
class TranscodeSessionEngine(
    private val ffmpegPath: () -> String,
    private val decoderPath: () -> String? = { null },
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

            running[key] = spawn(session, fromSegment) ?: return@withLock SessionAdmission.Unsupported
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

    /**
     * Brings the cache back under [TranscodeSettings.cacheCapBytes], evicting whole files least
     * recently written first until it fits. Two things are never evicted: a file whose session is in
     * [running] — its listener is on it now and FFmpeg is still writing into it — and an empty
     * directory, which frees nothing and is most likely one an encoder was just handed.
     *
     * The admission [mutex] is held only to read [running], never across the disk work: eviction can
     * take seconds on a full cache, and holding the lock would stall every [ensureRunning] behind it.
     * The check is repeated per candidate so a session admitted mid-sweep is still honoured.
     */
    internal suspend fun sweepCache() {
        val cap = settings.cacheCapBytes
        if (cap <= 0) return
        val cached = cache.listCachedFiles()
        var total = cached.sumOf { it.bytes }
        if (total <= cap) return
        for (candidate in cached.sortedBy { it.lastTouchedMs }) {
            if (total <= cap) break
            if (candidate.bytes == 0L || isRunning(candidate)) continue
            cache.evict(candidate.bookId, candidate.fileId)
            total -= candidate.bytes
            log.info {
                "transcode cache over cap: evicted ${candidate.bookId}/${candidate.fileId} (${candidate.bytes} bytes)"
            }
        }
    }

    private suspend fun isRunning(file: CachedFile): Boolean =
        mutex.withLock { key(file.bookId, file.fileId) in running }

    /**
     * Runs [sweepIdle] every [IDLE_SWEEP_MILLIS] and [sweepCache] every [CACHE_SWEEP_MILLIS] — plus
     * once at start, so a server that filled its cache and restarted reclaims the space at once —
     * for as long as [scope] lives. Started by DI wiring, not by tests.
     */
    fun startWatchdog(scope: CoroutineScope): Job =
        scope.launch {
            sweepCacheSafely()
            var lastCacheSweep = elapsedMillis()
            while (isActive) {
                delay(IDLE_SWEEP_MILLIS)
                sweepIdle()
                if (elapsedMillis() - lastCacheSweep >= CACHE_SWEEP_MILLIS) {
                    sweepCacheSafely()
                    lastCacheSweep = elapsedMillis()
                }
            }
        }

    /** A failed cache sweep is logged and retried next time; it must not take the idle sweep down with it. */
    private suspend fun sweepCacheSafely() {
        runCatchingCancellable { sweepCache() }
            .onFailure { log.warn(it) { "transcode cache sweep failed; will retry next interval" } }
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
    ): RunningSession? {
        val plan = HlsPlaylist.plan(session.durationMs, session.sampleRate, settings.targetSegmentSeconds)
        val commands =
            TranscodeCommand.forSession(
                ffmpegPath = ffmpegPath(),
                decoderPath = decoderPath(),
                session = session.copy(startSegment = fromSegment),
                startSeconds = fromSegment * plan.segmentSeconds,
                plan = plan,
                outputPattern = cache.segmentPattern(session.bookId, session.fileId),
                runListPath = cache.runListPath(session.bookId, session.fileId, fromSegment).toString(),
                bitrateKbps = settings.bitrateKbps,
            ) ?: return null
        cache.prepareDir(session.bookId, session.fileId)
        val spawner = newSpawner()
        spawner.start(commands)
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

        /**
         * How often [startWatchdog] walks the cache for eviction. A full recursive size walk is far
         * heavier than the idle check, and the cap is a working-set budget rather than a hard limit
         * — a few minutes over it is fine, a stat storm every ten seconds is not.
         */
        const val CACHE_SWEEP_MILLIS = 5 * 60_000L

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
