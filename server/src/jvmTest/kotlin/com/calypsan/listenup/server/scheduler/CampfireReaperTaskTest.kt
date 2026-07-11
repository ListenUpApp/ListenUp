@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.server.campfire.CampfireRegistry
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.testing.FixedClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * [CampfireReaperTask]'s discoverability nudge: an away-grace or idle reap that actually ends a
 * room broadcasts [SyncControl.CampfiresChanged] — the [ActiveSessionCleanupTask] precedent (nudge
 * only on an effectful sweep, never on an empty one). Anchor math and the pure sweeps themselves
 * are [CampfireRegistry]'s own tests ([com.calypsan.listenup.server.campfire.CampfireRegistryTest]).
 */
class CampfireReaperTaskTest :
    FunSpec({

        val t0 = Instant.fromEpochMilliseconds(1_730_000_000_000L)
        val settings = CampfireSettings(controlMode = CampfireControlMode.EVERYONE, inviteOnly = false)

        test("a reap that ends a room (away-grace, evicted down to empty) broadcasts CampfiresChanged") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(CampfireId("room-1"), "book-1", "host", "Host", settings, now = t0)
                registry.markAway(CampfireId("room-1"), "host", now = t0) // sole member away → reap ends the room

                val bus = ChangeBus()
                val received = mutableListOf<SyncControl>()
                val job = launch { bus.subscribeControl().collect { received += it.control } }
                advanceUntilIdle()

                val task = CampfireReaperTask(registry = registry, bus = bus, clock = FixedClock(t0 + 3.minutes))
                task.runOnce()
                advanceUntilIdle()

                received shouldBe listOf(SyncControl.CampfiresChanged)
                job.cancel()
            }
        }

        test("a sweep that reaps nothing does not broadcast") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(CampfireId("room-1"), "book-1", "host", "Host", settings, now = t0)

                val bus = ChangeBus()
                val received = mutableListOf<SyncControl>()
                val job = launch { bus.subscribeControl().collect { received += it.control } }
                advanceUntilIdle()

                val task = CampfireReaperTask(registry = registry, bus = bus, clock = FixedClock(t0))
                task.runOnce()
                advanceUntilIdle()

                received.shouldBeEmpty()
                job.cancel()
            }
        }
    })
