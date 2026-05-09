@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Instant

class TagRepositoryUpsertTest :
    FunSpec({

        test("upsert of a fresh row publishes Created with bumped revision") {
            withInMemoryDatabase {
                val bus = ChangeBus()
                val fixedTime = Instant.fromEpochMilliseconds(1_730_000_000_000L)
                val repo =
                    TagRepository(
                        db = this,
                        bus = bus,
                        clock =
                            object : Clock {
                                override fun now() = fixedTime
                            },
                    )

                runTest {
                    val deferredBusEvent = async { bus.subscribe().first() }
                    advanceUntilIdle()

                    val initial = Tag(id = "t1", name = "sci-fi", revision = 0, updatedAt = 0)
                    val result = repo.upsert(initial, clientOpId = "op-1")

                    result.shouldBeInstanceOf<AppResult.Success<Tag>>()
                    val saved = (result as AppResult.Success).data
                    saved.id shouldBe "t1"
                    saved.name shouldBe "sci-fi"
                    saved.revision shouldBe 1L
                    saved.updatedAt shouldBe 1_730_000_000_000L

                    val busEvent = deferredBusEvent.await()
                    busEvent.domainName shouldBe "tags"
                    val event = busEvent.event
                    event.shouldBeInstanceOf<SyncEvent.Created<Tag>>()
                    event.id shouldBe "t1"
                    event.revision shouldBe 1L
                    event.clientOpId shouldBe "op-1"
                    (event as SyncEvent.Created<*>).payload shouldBe saved
                }
            }
        }

        test("upsert of an existing row publishes Updated") {
            withInMemoryDatabase {
                val bus = ChangeBus()
                val repo = TagRepository(db = this, bus = bus)

                runTest {
                    val initial = Tag(id = "t1", name = "sci-fi", revision = 0, updatedAt = 0)
                    repo.upsert(initial)

                    val sub = async { bus.subscribe().first() }
                    advanceUntilIdle()

                    val updated = initial.copy(name = "scifi-updated")
                    repo.upsert(updated, clientOpId = "op-2")

                    val busEvent = sub.await()
                    busEvent.domainName shouldBe "tags"
                    busEvent.event.shouldBeInstanceOf<SyncEvent.Updated<Tag>>()
                    busEvent.event.clientOpId shouldBe "op-2"
                }
            }
        }

        test("upsert with null clientOpId publishes event with null clientOpId") {
            withInMemoryDatabase {
                val bus = ChangeBus()
                val repo = TagRepository(db = this, bus = bus)

                runTest {
                    val deferredBusEvent = async { bus.subscribe().first() }
                    advanceUntilIdle()

                    repo.upsert(Tag(id = "t2", name = "x", revision = 0, updatedAt = 0))

                    val busEvent = deferredBusEvent.await()
                    busEvent.event.clientOpId shouldBe null
                }
            }
        }

        test("revisions are strictly monotonic across writes") {
            withInMemoryDatabase {
                val bus = ChangeBus()
                val repo = TagRepository(db = this, bus = bus)

                runTest {
                    val r1 = (repo.upsert(Tag("a", "n1", 0, 0)) as AppResult.Success).data.revision
                    val r2 = (repo.upsert(Tag("b", "n2", 0, 0)) as AppResult.Success).data.revision
                    val r3 = (repo.upsert(Tag("a", "n1-updated", 0, 0)) as AppResult.Success).data.revision

                    listOf(r1, r2, r3).distinct().size shouldBe 3
                    (r2 > r1) shouldBe true
                    (r3 > r2) shouldBe true
                }
            }
        }
    })
