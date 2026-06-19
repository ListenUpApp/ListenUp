@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.LibraryFolderSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withInMemoryDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class LibraryFolderRepositoryTest :
    FunSpec({

        test("upsert inserts a new folder and publishes Created") {
            withInMemoryDatabase {
                // Seed the parent library so the FK constraint is satisfied.
                seedTestLibraryAndFolder(libraryId = "lib1", folderId = "seed-folder")
                val bus = ChangeBus()
                val repo = LibraryFolderRepository(db = this, bus = bus, registry = SyncRegistry())
                runTest {
                    val deferred = async { bus.subscribe().first() }
                    advanceUntilIdle()

                    val payload = folderPayload(id = "f1", libraryId = "lib1", rootPath = "/srv/audio")
                    val result = repo.upsert(payload)

                    result.shouldBeInstanceOf<AppResult.Success<LibraryFolderSyncPayload>>()
                    result as AppResult.Success
                    result.data.id shouldBe "f1"
                    result.data.libraryId shouldBe "lib1"
                    result.data.rootPath shouldBe "/srv/audio"

                    val busEvent = deferred.await()
                    busEvent.repo.domainName shouldBe "library_folders"
                    busEvent.event.shouldBeInstanceOf<SyncEvent.Created<LibraryFolderSyncPayload>>()
                    busEvent.event.id shouldBe "f1"
                }
            }
        }

        test("upsert on an existing folder publishes Updated") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder(libraryId = "lib1", folderId = "seed-folder")
                val bus = ChangeBus()
                val repo = LibraryFolderRepository(db = this, bus = bus, registry = SyncRegistry())
                runTest {
                    // Subscribe before the first upsert — drop the Created event, await Updated.
                    val deferred = async { bus.subscribe().drop(1).first() }
                    advanceUntilIdle()

                    repo.upsert(folderPayload(id = "f1", libraryId = "lib1", rootPath = "/old"))
                    repo.upsert(folderPayload(id = "f1", libraryId = "lib1", rootPath = "/new"))

                    val busEvent = deferred.await()
                    busEvent.event.shouldBeInstanceOf<SyncEvent.Updated<LibraryFolderSyncPayload>>()
                    (busEvent.event as SyncEvent.Updated<LibraryFolderSyncPayload>).payload.rootPath shouldBe "/new"
                }
            }
        }

        test("softDelete marks the folder as tombstoned and publishes Deleted") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder(libraryId = "lib1", folderId = "seed-folder")
                val bus = ChangeBus()
                val repo = LibraryFolderRepository(db = this, bus = bus, registry = SyncRegistry())
                runTest {
                    // Subscribe before the first upsert — drop the Created event, await Deleted.
                    val deferred = async { bus.subscribe().drop(1).first() }
                    advanceUntilIdle()

                    repo.upsert(folderPayload(id = "f1", libraryId = "lib1", rootPath = "/srv/audio"))
                    val result = repo.softDelete(FolderId("f1"))
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    val busEvent = deferred.await()
                    busEvent.event.shouldBeInstanceOf<SyncEvent.Deleted>()
                    busEvent.event.id shouldBe "f1"
                }
            }
        }

        test("softDelete on a missing folder returns Failure") {
            withInMemoryDatabase {
                val repo =
                    LibraryFolderRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val result = repo.softDelete(FolderId("does-not-exist"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                }
            }
        }

        test("pullSince returns only rows with revision > cursor") {
            withInMemoryDatabase {
                seedTestLibraryAndFolder(libraryId = "lib1", folderId = "seed-folder")
                val repo =
                    LibraryFolderRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val afterFirst =
                        repo.upsert(folderPayload(id = "f1", libraryId = "lib1", rootPath = "/first"))
                    val cursorAfterFirst = (afterFirst as AppResult.Success).data.revision

                    repo.upsert(folderPayload(id = "f2", libraryId = "lib1", rootPath = "/second"))

                    val page = repo.pullSince(userId = null, cursor = cursorAfterFirst, limit = 100)
                    page.items.size shouldBe 1
                    page.items.first().id shouldBe "f2"
                    page.hasMore shouldBe false
                }
            }
        }

        test("idAsString unwraps the value class to its raw string") {
            withInMemoryDatabase {
                val repo =
                    LibraryFolderRepository(db = this, bus = ChangeBus(), registry = SyncRegistry())
                repo.idAsStringForTest(FolderId("abc-123")) shouldBe "abc-123"
            }
        }
    })

private fun folderPayload(
    id: String,
    libraryId: String,
    rootPath: String,
): LibraryFolderSyncPayload =
    LibraryFolderSyncPayload(
        id = id,
        libraryId = libraryId,
        rootPath = rootPath,
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
