package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.server.campfire.CampfireRegistry
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.util.runCatchingCancellable
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private val log = loggerFor<CampfireReaperTask>()

/**
 * Periodic sweep over the in-memory [CampfireRegistry]: evicts members away past the 2-minute
 * grace window (each eviction potentially ending an emptied or host-departed room) and ends rooms
 * idle past their activity timeout — the [CampfireRegistry.reapAwayMembers] / [CampfireRegistry.reapIdle]
 * pure sweeps, run on a schedule the same way [ActiveSessionCleanupTask] sweeps stale sessions.
 *
 * A 30-second [interval] — much tighter than [ActiveSessionCleanupTask]'s 5 minutes — because the
 * away grace itself is only 2 minutes; a coarser sweep would let a departed member's seat sit
 * stale for most of the grace window before the reaper ever looks, visibly delaying the
 * `MemberLeft` frame (and any resulting host handoff) other members see.
 *
 * Runs on the supplied [CoroutineScope]; the caller cancels the returned [Job] when the
 * application stops. The loop re-raises [kotlinx.coroutines.CancellationException] so structured
 * concurrency is respected, and suppresses all other exceptions with a warning log so a transient
 * failure does not stop the sweep permanently.
 *
 * A reap that ends any room changes what [com.calypsan.listenup.api.CampfireService.listOpenSessions]
 * can return, so [runOnce] broadcasts [SyncControl.CampfiresChanged] whenever it actually ends a
 * room — the [ActiveSessionCleanupTask] precedent (nudge only on an effectful sweep, never on an
 * empty one).
 */
internal class CampfireReaperTask(
    private val registry: CampfireRegistry,
    private val bus: ChangeBus,
    private val clock: Clock = Clock.System,
    private val interval: Duration = 30.seconds,
) {
    /** Start the sweep loop on [scope]. Returns the [Job] — cancel it to stop. */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                runCatchingCancellable { runOnce() }
                    .onFailure { log.warn(it) { "CampfireReaperTask sweep failed; will retry next interval" } }
                delay(interval)
            }
        }

    /** Runs one away-grace + idle sweep. Exposed for tests that want a single deterministic pass. */
    suspend fun runOnce() {
        val now = clock.now()
        val endedForAway = registry.reapAwayMembers(now)
        val endedForIdle = registry.reapIdle(now)
        val endedTotal = endedForAway.size + endedForIdle.size
        if (endedTotal > 0) {
            log.info { "CampfireReaperTask ended $endedTotal room(s) (away-grace=${endedForAway.size}, idle=${endedForIdle.size})" }
            bus.broadcastControl(SyncControl.CampfiresChanged)
        }
    }
}
