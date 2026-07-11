@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.scheduler

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.server.campfire.CampfireRegistry
import com.calypsan.listenup.server.services.ActivityRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.activityRecorder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
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
 * The "listened together" activity recording (Task 5) is covered here too, since it is the reaper
 * that detects an away-grace or idle ending.
 */
class CampfireReaperTaskTest :
    FunSpec({

        val t0 = Instant.fromEpochMilliseconds(1_730_000_000_000L)
        val settings = CampfireSettings(controlMode = CampfireControlMode.EVERYONE, inviteOnly = false)

        test("a reap that ends a room (away-grace, evicted down to empty) broadcasts CampfiresChanged") {
            withSqlDatabase {
                runTest {
                    val registry = CampfireRegistry(clock = FixedClock(t0))
                    registry.createRoom(CampfireId("room-1"), "book-1", "host", "Host", settings, now = t0)
                    registry.markAway(CampfireId("room-1"), "host", now = t0) // sole member away → reap ends the room

                    val bus = ChangeBus()
                    val received = mutableListOf<SyncControl>()
                    val job = launch { bus.subscribeControl().collect { received += it.control } }
                    advanceUntilIdle()

                    val task =
                        CampfireReaperTask(
                            registry = registry,
                            bus = bus,
                            activityRecorder = activityRecorder(bus),
                            clock = FixedClock(t0 + 3.minutes),
                        )
                    task.runOnce()
                    advanceUntilIdle()

                    received shouldBe listOf(SyncControl.CampfiresChanged)
                    job.cancel()
                }
            }
        }

        test("a sweep that reaps nothing does not broadcast") {
            withSqlDatabase {
                runTest {
                    val registry = CampfireRegistry(clock = FixedClock(t0))
                    registry.createRoom(CampfireId("room-1"), "book-1", "host", "Host", settings)

                    val bus = ChangeBus()
                    val received = mutableListOf<SyncControl>()
                    val job = launch { bus.subscribeControl().collect { received += it.control } }
                    advanceUntilIdle()

                    val task =
                        CampfireReaperTask(
                            registry = registry,
                            bus = bus,
                            activityRecorder = activityRecorder(bus),
                            clock = FixedClock(t0),
                        )
                    task.runOnce()
                    advanceUntilIdle()

                    received.shouldBeEmpty()
                    job.cancel()
                }
            }
        }

        // ---- "Listened together" activity (Task 5) ----

        test("an idle reap that ends a >=2-participant room records CAMPFIRE_TOGETHER, attributed to the host") {
            withSqlDatabase {
                runTest {
                    val registry = CampfireRegistry(clock = FixedClock(t0))
                    registry.createRoom(CampfireId("room-1"), "book-1", "host", "Host", settings, now = t0)
                    registry.join(CampfireId("room-1"), "u2", "Two", now = t0)

                    val task =
                        CampfireReaperTask(
                            registry = registry,
                            bus = ChangeBus(),
                            activityRecorder = activityRecorder(),
                            clock = FixedClock(t0 + 61.minutes),
                        )
                    task.runOnce()

                    val rows = ActivityRepository(db = sql).page(before = null, limit = 10)
                    rows shouldHaveSize 1
                    rows.single().type shouldBe ActivityType.CAMPFIRE_TOGETHER
                    rows.single().userId shouldBe "host"
                    rows.single().bookId shouldBe "book-1"
                }
            }
        }

        test("an idle reap that ends a solo (1-participant) room does NOT record an activity") {
            withSqlDatabase {
                runTest {
                    val registry = CampfireRegistry(clock = FixedClock(t0))
                    registry.createRoom(CampfireId("room-1"), "book-1", "host", "Host", settings, now = t0)

                    val task =
                        CampfireReaperTask(
                            registry = registry,
                            bus = ChangeBus(),
                            activityRecorder = activityRecorder(),
                            clock = FixedClock(t0 + 61.minutes),
                        )
                    task.runOnce()

                    ActivityRepository(db = sql).page(before = null, limit = 10).shouldBeEmpty()
                }
            }
        }

        test("an away-grace reap that empties a >=2-participant room records CAMPFIRE_TOGETHER") {
            withSqlDatabase {
                runTest {
                    val registry = CampfireRegistry(clock = FixedClock(t0))
                    registry.createRoom(CampfireId("room-1"), "book-1", "host", "Host", settings, now = t0)
                    registry.join(CampfireId("room-1"), "u2", "Two", now = t0)
                    registry.leave(CampfireId("room-1"), "u2", now = t0) // room continues — host still present
                    registry.markAway(CampfireId("room-1"), "host", now = t0)

                    val task =
                        CampfireReaperTask(
                            registry = registry,
                            bus = ChangeBus(),
                            activityRecorder = activityRecorder(),
                            clock = FixedClock(t0 + 3.minutes),
                        )
                    task.runOnce()

                    val rows = ActivityRepository(db = sql).page(before = null, limit = 10)
                    rows shouldHaveSize 1
                    rows.single().type shouldBe ActivityType.CAMPFIRE_TOGETHER
                }
            }
        }
    })
