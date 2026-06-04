@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ListeningEventSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.ListeningEventId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.noOpPublicProfileMaintainer
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class ListeningEventRepositoryTest :
    FunSpec({

        test("upsert inserts a new event and publishes a Created BusEvent for that userId") {
            withInMemoryDatabase {
                val bus = ChangeBus()
                val repo = ListeningEventRepository(db = this, bus = bus, registry = SyncRegistry())
                runTest {
                    val deferred = async { bus.subscribe().first() }
                    advanceUntilIdle()

                    val payload = listeningEventPayload("evt-1", "book-1")
                    val result = repo.upsert(payload, clientOpId = null, userId = "u1")
                    result.shouldBeInstanceOf<AppResult.Success<*>>()

                    val busEvent = deferred.await()
                    busEvent.userId shouldBe "u1"
                    busEvent.repo.domainName shouldBe "listening_events"
                    busEvent.event.shouldBeInstanceOf<SyncEvent.Created<*>>()
                }
            }
        }

        test("upsert of an existing id is idempotent — domain fields unchanged, revision advances") {
            withInMemoryDatabase {
                val repo = ListeningEventRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val original = listeningEventPayload("evt-2", "book-2", startPositionMs = 1_000L)
                    val first = (repo.upsert(original, clientOpId = null, userId = "u1") as AppResult.Success).data

                    // Re-upsert with different domain fields — should be ignored
                    val duplicate = original.copy(startPositionMs = 9_999L)
                    val second = (repo.upsert(duplicate, clientOpId = null, userId = "u1") as AppResult.Success).data

                    // Domain field unchanged
                    second.startPositionMs shouldBe 1_000L
                    // Revision advanced
                    (second.revision > first.revision) shouldBe true
                }
            }
        }

        test("pullSince(userId = u1) returns only u1's events") {
            withInMemoryDatabase {
                val repo = ListeningEventRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(listeningEventPayload("evt-u1", "book-1"), clientOpId = null, userId = "u1")
                    repo.upsert(listeningEventPayload("evt-u2", "book-1"), clientOpId = null, userId = "u2")

                    val page = repo.pullSince(userId = "u1", cursor = 0L, limit = 50)
                    page.items.size shouldBe 1
                    page.items.first().id shouldBe "evt-u1"
                }
            }
        }

        test("pullSince with null userId fails fast for user-scoped domain") {
            withInMemoryDatabase {
                val repo = ListeningEventRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val threw = runCatching { repo.pullSince(userId = null, cursor = 0L, limit = 50) }
                    threw.isFailure shouldBe true
                }
            }
        }

        test("idAsString unwraps the value class to its raw string") {
            withInMemoryDatabase {
                val repo = ListeningEventRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                repo.idAsStringForTest(ListeningEventId("evt-1")) shouldBe "evt-1"
            }
        }

        test("upsert with userStatsUpdater wired fires onListeningEvent and materialises totalSecondsAllTime") {
            withInMemoryDatabase {
                val statsRepo = UserStatsRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                val updater =
                    UserStatsUpdater(
                        db = this,
                        userStatsRepo = statsRepo,
                        publicProfileMaintainerProvider = { noOpPublicProfileMaintainer() },
                    )
                val repo =
                    ListeningEventRepository(
                        db = this,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        userStatsUpdater = updater,
                    )
                runTest {
                    // 60-second span
                    val payload = listeningEventPayload("evt-wire-1", "book-wire-1")
                    repo.upsert(payload, clientOpId = null, userId = "u-wire")

                    val stats = statsRepo.getForUser("u-wire").shouldNotBeNull()
                    // startedAt = 1_730_000_000_000L, endedAt = 1_730_000_060_000L → 60 s
                    stats.totalSecondsAllTime shouldBe 60L
                }
            }
        }
    })

private fun listeningEventPayload(
    id: String,
    bookId: String,
    startPositionMs: Long = 0L,
): ListeningEventSyncPayload =
    ListeningEventSyncPayload(
        id = id,
        bookId = bookId,
        startPositionMs = startPositionMs,
        endPositionMs = startPositionMs + 60_000L,
        startedAt = 1_730_000_000_000L,
        endedAt = 1_730_000_060_000L,
        playbackSpeed = 1.0f,
        tz = "Europe/London",
        deviceLabel = null,
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
