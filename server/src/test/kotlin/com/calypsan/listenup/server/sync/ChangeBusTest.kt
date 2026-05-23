@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Bus mechanics, driven through a real [TagRepository] so events carry the
 * type-bound [BusEvent.repo] reference produced by the canonical publish path.
 */
class ChangeBusTest :
    FunSpec({

        test("publish then subscribe receives the event") {
            withInMemoryDatabase {
                val bus = ChangeBus()
                val repo = TagRepository(db = this, bus = bus, registry = SyncRegistry())
                runTest {
                    val deferred = async { bus.subscribe().first() }
                    advanceUntilIdle()
                    repo.upsert(Tag(id = "a", name = "n", revision = 0, updatedAt = 0))
                    val busEvent = deferred.await()
                    busEvent.repo.domainName shouldBe "tags"
                    busEvent.event.id shouldBe "a"
                }
            }
        }

        test("multiple subscribers each receive every event") {
            withInMemoryDatabase {
                val bus = ChangeBus()
                val repo = TagRepository(db = this, bus = bus, registry = SyncRegistry())
                runTest {
                    val sub1 = async { bus.subscribe().take(2).toList() }
                    val sub2 = async { bus.subscribe().take(2).toList() }
                    advanceUntilIdle()
                    repo.upsert(Tag(id = "a", name = "n1", revision = 0, updatedAt = 0))
                    repo.upsert(Tag(id = "b", name = "n2", revision = 0, updatedAt = 0))

                    val r1 = sub1.await()
                    val r2 = sub2.await()
                    r1 shouldHaveSize 2
                    r2 shouldHaveSize 2
                    r1.map { it.event.id } shouldBe listOf("a", "b")
                    r2.map { it.event.id } shouldBe listOf("a", "b")
                }
            }
        }

        test("oldestRetainedRevision tracks the lowest in-buffer event") {
            withInMemoryDatabase {
                val bus = ChangeBus()
                val repo = TagRepository(db = this, bus = bus, registry = SyncRegistry())
                runTest {
                    bus.oldestRetainedRevision() shouldBe null
                    repo.upsert(Tag(id = "a", name = "x", revision = 0, updatedAt = 0))
                    repo.upsert(Tag(id = "b", name = "y", revision = 0, updatedAt = 0))
                    bus.oldestRetainedRevision()!! shouldBeGreaterThanOrEqual 1L
                }
            }
        }

        test("BusEvent is type-bound to its source repository") {
            withInMemoryDatabase {
                val bus = ChangeBus()
                val repo = TagRepository(db = this, bus = bus, registry = SyncRegistry())
                runTest {
                    val deferred = async { bus.subscribe().first() }
                    advanceUntilIdle()
                    repo.upsert(Tag(id = "a", name = "n", revision = 0, updatedAt = 0))
                    val busEvent = deferred.await()
                    busEvent.repo.shouldBeInstanceOf<TagRepository>()
                    busEvent.repo shouldBe repo
                }
            }
        }

        test("publish carries an optional userId on the BusEvent") {
            withInMemoryDatabase {
                val bus = ChangeBus()
                val fakeRepo = TagRepository(db = this, bus = bus, registry = SyncRegistry())
                val fakeEvent = SyncEvent.Created(id = "x", revision = 1L, occurredAt = 0L, payload = Tag(id = "x", name = "e1", revision = 1, updatedAt = 0))
                val fakeEvent2 = SyncEvent.Created(id = "y", revision = 2L, occurredAt = 0L, payload = Tag(id = "y", name = "e2", revision = 2, updatedAt = 0))
                runTest {
                    val sub = async { bus.subscribe().take(2).toList() }
                    advanceUntilIdle()
                    bus.publish(repo = fakeRepo, event = fakeEvent, userId = "u1")
                    bus.publish(repo = fakeRepo, event = fakeEvent2, userId = null)
                    val events = sub.await()
                    events[0].userId shouldBe "u1"
                    events[1].userId shouldBe null
                }
            }
        }
    })
