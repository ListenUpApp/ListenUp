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
                    val deferredEvent = async { bus.subscribe().first() }

                    val result = repo.softDelete("t1", clientOpId = "op-del")
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val event = deferredEvent.await()
                    event.shouldBeInstanceOf<SyncEvent.Deleted>()
                    event.id shouldBe "t1"
                    event.clientOpId shouldBe "op-del"
                    (event.revision > 1L) shouldBe true
                }
            }
        }

        test("softDelete of non-existent id returns Failure") {
            withInMemoryDatabase {
                val db = this
                val repo = TagRepository(db, ChangeBus())
                runTest {
                    val result = repo.softDelete("does-not-exist")
                    result.shouldBeInstanceOf<AppResult.Failure>()
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
                    val deferredEvent = async { bus.subscribe().first() }

                    val resurrected = repo.upsert(Tag("t1", "sci-fi-resurrected", 0, 0))
                    resurrected.shouldBeInstanceOf<AppResult.Success<Tag>>()
                    (resurrected as AppResult.Success).data.deletedAt shouldBe null

                    val event = deferredEvent.await()
                    event.shouldBeInstanceOf<SyncEvent.Updated<Tag>>()
                }
            }
        }
    })
