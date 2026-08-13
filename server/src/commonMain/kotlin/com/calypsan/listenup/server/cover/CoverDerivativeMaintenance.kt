package com.calypsan.listenup.server.cover

import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.util.runCatchingCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

private val log = loggerFor<CoverDerivativeMaintenance>()

/** A live book's cover, identified by the content hash its derivatives are keyed on. */
data class LiveCover(
    val bookId: BookId,
    val coverHash: String,
)

/**
 * Keeps the derivative cache warm and free of orphans.
 *
 * ⚠️ **Nothing here is on a correctness path, and that is the design.** `?w=` generates on demand,
 * so a pass that never runs, dies halfway, or fails outright costs a client one ~50ms generation
 * instead of a cache hit. That is what lets this be a plain background job with no retry ledger, no
 * resume cursor, and no bearing on whether a scan or a boot succeeded.
 *
 * **Warming** walks every live cover and fills in the rungs it can reach. It is idempotent by
 * construction — an existing derivative costs a `stat` — so a repeat pass over a warm library is
 * effectively free, and a pass interrupted at any point simply resumes as the next pass.
 *
 * **Sweeping** is the whole reclamation story for the cache. Because derivatives are keyed by cover
 * hash, nothing deletes them at the moment a book is re-covered or removed — the old file just
 * stops being asked for. This pass is what eventually notices.
 *
 * ⛔ **Runs strictly one cover at a time.** Encoding is CPU-bound and this competes with the
 * scanner, which is the slow moment that actually matters to a user. A background warm-up that
 * makes a first scan slower has taken more than it gives.
 *
 * @param derivatives the cache being maintained.
 * @param liveCovers every live book that has a cover, with its hash.
 * @param originalBytes the book's full-size cover bytes, or `null` when they cannot be read.
 */
class CoverDerivativeMaintenance(
    private val derivatives: CoverDerivatives,
    private val liveCovers: suspend () -> List<LiveCover>,
    private val originalBytes: suspend (BookId) -> ByteArray?,
) {
    /**
     * Rungs this process has already found unreachable, as `<hash>@<rung>`. Without it every pass
     * re-reads the full-size bytes of every cover whose top rung the decoder cannot reach — a
     * decline writes no file, so nothing on disk remembers the attempt.
     *
     * ⛔ **In memory, never on disk**, and that distinction is the whole argument. What could make a
     * decline stop being true is a change to the codec, which arrives as a new binary and therefore
     * a fresh process — so a set that dies with the process expires at exactly the right moment,
     * where a cached file on disk would outlive its reason. Touched only from [runOnce], which the
     * loop runs one pass at a time.
     */
    private val declined = mutableSetOf<String>()

    /**
     * Starts the maintenance loop on [scope] and returns its [Job] — cancel it to stop.
     *
     * The first pass waits a minute, long enough for a boot-time scan to get going: a cold
     * library has no covers to warm yet, and a warm one is in no hurry.
     */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            delay(STARTUP_DELAY)
            while (isActive) {
                runCatchingCancellable { runOnce() }
                    .onFailure { log.warn(it) { "cover derivative maintenance failed; will retry next interval" } }
                delay(INTERVAL)
            }
        }

    /** One warm-and-sweep pass. Testable without a running loop. */
    suspend fun runOnce() {
        val covers = liveCovers()
        var generated = 0
        for (cover in covers) {
            generated += warm(cover)
        }
        val swept = derivatives.sweepOrphans(covers.mapTo(mutableSetOf()) { it.coverHash })
        if (generated > 0 || swept > 0) {
            log.info { "cover derivatives: generated $generated, swept $swept orphan(s) over ${covers.size} cover(s)" }
        }
    }

    /**
     * Fills in [cover]'s missing rungs, answering how many this call actually generated. A rung the
     * source cannot reach, and a cover whose bytes will not read, are both quiet skips — one bad
     * book must not end a pass over a whole library.
     */
    private suspend fun warm(cover: LiveCover): Int {
        // Every rung derives from the same bytes, so the read happens at most once per cover no
        // matter how many rungs miss. That matters because the read is the expensive half: for an
        // embedded cover it parses the audio file, and the top rung routinely declines on a source
        // too small to reach it — which would otherwise pay for a read it then throws away.
        var source: ByteArray? = null
        var read = false

        suspend fun sourceBytes(): ByteArray? {
            if (!read) {
                read = true
                source = originalBytes(cover.bookId)
            }
            return source
        }

        var generated = 0
        for (rung in derivatives.rungs) {
            if ("${cover.coverHash}@$rung" in declined) continue
            val outcome =
                runCatchingCancellable {
                    derivatives.warm(cover.coverHash, rung) { sourceBytes() }
                }.getOrElse { e ->
                    log.warn(e) { "cover derivative warm failed for ${cover.bookId.value} at ${rung}px" }
                    WarmResult.DECLINED
                }
            when (outcome) {
                WarmResult.GENERATED -> generated++
                WarmResult.DECLINED -> declined += "${cover.coverHash}@$rung"
                WarmResult.ALREADY_PRESENT -> Unit
            }
        }
        return generated
    }

    private companion object {
        /** Long enough to stay out of a boot-time scan's way; short enough to matter to a first visit. */
        val STARTUP_DELAY = 60.seconds

        /** The sweep is the only time-sensitive half, and orphans are measured in kilobytes. */
        val INTERVAL = 1.days
    }
}
