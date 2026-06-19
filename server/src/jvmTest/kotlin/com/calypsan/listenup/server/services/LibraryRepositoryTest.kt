@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.LibrarySyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class LibraryRepositoryTest :
    FunSpec({

        test("upsert inserts a new library and publishes Created") {
            withInMemoryDatabase {
                val bus = ChangeBus()
                val repo = LibraryRepository(db = this, bus = bus, registry = SyncRegistry())
                runTest {
                    val deferred = async { bus.subscribe().first() }
                    advanceUntilIdle()

                    val payload = libraryPayload(id = "lib1", name = "My Library")
                    val result = repo.upsert(payload)

                    result.shouldBeInstanceOf<AppResult.Success<LibrarySyncPayload>>()
                    result as AppResult.Success
                    result.data.id shouldBe "lib1"
                    result.data.name shouldBe "My Library"
                    result.data.metadataPrecedence shouldBe "embedded,abs,sidecar"
                    result.data.accessMode shouldBe "shared"
                    result.data.createdByUserId shouldBe null

                    val busEvent = deferred.await()
                    busEvent.repo.domainName shouldBe "libraries"
                    busEvent.event.shouldBeInstanceOf<SyncEvent.Created<LibrarySyncPayload>>()
                    busEvent.event.id shouldBe "lib1"
                }
            }
        }

        test("upsert on an existing library publishes Updated") {
            withInMemoryDatabase {
                val bus = ChangeBus()
                val repo = LibraryRepository(db = this, bus = bus, registry = SyncRegistry())
                runTest {
                    // Subscribe before the first upsert — the bus replays, so we drop
                    // the Created event and await only the Updated that follows.
                    val deferred = async { bus.subscribe().drop(1).first() }
                    advanceUntilIdle()

                    repo.upsert(libraryPayload(id = "lib1", name = "Original Name"))
                    repo.upsert(libraryPayload(id = "lib1", name = "Updated Name"))

                    val busEvent = deferred.await()
                    busEvent.event.shouldBeInstanceOf<SyncEvent.Updated<LibrarySyncPayload>>()
                    (busEvent.event as SyncEvent.Updated<LibrarySyncPayload>).payload.name shouldBe "Updated Name"
                }
            }
        }

        test("softDelete marks the library as tombstoned and publishes Deleted") {
            withInMemoryDatabase {
                val bus = ChangeBus()
                val repo = LibraryRepository(db = this, bus = bus, registry = SyncRegistry())
                runTest {
                    // Subscribe before the first upsert — drop the Created event, await Deleted.
                    val deferred = async { bus.subscribe().drop(1).first() }
                    advanceUntilIdle()

                    repo.upsert(libraryPayload(id = "lib1", name = "To Delete"))
                    val result = repo.softDelete(LibraryId("lib1"))
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val busEvent = deferred.await()
                    busEvent.event.shouldBeInstanceOf<SyncEvent.Deleted>()
                    busEvent.event.id shouldBe "lib1"
                }
            }
        }

        test("softDelete on a missing library returns Failure") {
            withInMemoryDatabase {
                val repo = LibraryRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val result = repo.softDelete(LibraryId("does-not-exist"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                }
            }
        }

        test("pullSince returns only rows with revision > cursor") {
            withInMemoryDatabase {
                val repo = LibraryRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(libraryPayload(id = "lib1", name = "First"))
                    val afterFirst = repo.upsert(libraryPayload(id = "lib1", name = "First"))
                    val cursorAfterFirst =
                        (afterFirst as AppResult.Success).data.revision

                    repo.upsert(libraryPayload(id = "lib2", name = "Second"))

                    val page = repo.pullSince(userId = null, cursor = cursorAfterFirst, limit = 100)
                    page.items.size shouldBe 1
                    page.items.first().id shouldBe "lib2"
                    page.hasMore shouldBe false
                }
            }
        }

        test("idAsString unwraps the value class to its raw string") {
            withInMemoryDatabase {
                val repo = LibraryRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                repo.idAsStringForTest(LibraryId("abc-123")) shouldBe "abc-123"
            }
        }
    })

private fun libraryPayload(
    id: String,
    name: String,
    metadataPrecedence: String = "embedded,abs,sidecar",
    accessMode: String = "shared",
    createdByUserId: String? = null,
): LibrarySyncPayload =
    LibrarySyncPayload(
        id = id,
        name = name,
        metadataPrecedence = metadataPrecedence,
        accessMode = accessMode,
        createdByUserId = createdByUserId,
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
