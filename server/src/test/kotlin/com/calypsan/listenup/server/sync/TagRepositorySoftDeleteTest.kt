@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class TagRepositorySoftDeleteTest :
    FunSpec({

        test("softDelete sets deletedAt, bumps revision, publishes Deleted") {
            withInMemoryDatabase {
                val db = this
                val bus = ChangeBus()
                val repo = TagRepository(db, bus)
                runTest {
                    repo.upsert(Tag("t1", "sci-fi", 0, 0))
                    // With replay=256, the subscriber sees the cached Created event first.
                    // Drop it to observe only the Deleted event from softDelete.
                    val deferredBusEvent = async { bus.subscribe().drop(1).first() }
                    advanceUntilIdle()

                    val result = repo.softDelete("t1", clientOpId = "op-del")
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val busEvent = deferredBusEvent.await()
                    busEvent.domainName shouldBe "tags"
                    val event = busEvent.event
                    event.shouldBeInstanceOf<SyncEvent.Deleted>()
                    event.id shouldBe "t1"
                    event.clientOpId shouldBe "op-del"
                    (event.revision > 1L) shouldBe true
                }
            }
        }

        test("softDelete of non-existent id returns SyncError.NotFound") {
            withInMemoryDatabase {
                val db = this
                val repo = TagRepository(db, ChangeBus())
                runTest {
                    val result = repo.softDelete("does-not-exist")
                    result.shouldBeInstanceOf<AppResult.Failure>()
                    val err = (result as AppResult.Failure).error
                    err.shouldBeInstanceOf<SyncError.NotFound>()
                    (err as SyncError.NotFound).domain shouldBe "tags"
                    err.entityId shouldBe "does-not-exist"
                }
            }
        }

        test("upsert of a soft-deleted row clears deletedAt and emits Updated") {
            withInMemoryDatabase {
                val db = this
                val bus = ChangeBus()
                val repo = TagRepository(db, bus)
                runTest {
                    repo.upsert(Tag("t1", "sci-fi", 0, 0))
                    repo.softDelete("t1")
                    // With replay=256, the subscriber sees both cached events (Created + Deleted).
                    // Drop them to observe only the Updated event from the resurrection upsert.
                    val deferredBusEvent = async { bus.subscribe().drop(2).first() }
                    advanceUntilIdle()

                    val resurrected = repo.upsert(Tag("t1", "sci-fi-resurrected", 0, 0))
                    resurrected.shouldBeInstanceOf<AppResult.Success<Tag>>()
                    (resurrected as AppResult.Success).data.deletedAt shouldBe null

                    val busEvent = deferredBusEvent.await()
                    busEvent.event.shouldBeInstanceOf<SyncEvent.Updated<Tag>>()
                }
            }
        }
    })
