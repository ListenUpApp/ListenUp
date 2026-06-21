@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.LibraryFolderSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Tests for [LibraryFolderRepository] — a global (cross-user) SQLDelight aggregate
 * whose rows belong to a parent library.
 *
 * Every test runs against a real migrated database exposed as a SQLDelight
 * [com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase] (`sql`, the repo's view)
 * and a [app.cash.sqldelight.db.SqlDriver] (`driver`) (see
 * [com.calypsan.listenup.server.testing.withSqlDatabase]).
 *
 * Coverage: the base substrate path (create/update/delete + pull cursor), the
 * library→folder enumeration ([LibraryFolderRepository.listByLibrary]), the natural-key
 * root-path lookup ([LibraryFolderRepository.findLiveByRootPath]), and the FK
 * `ON DELETE CASCADE` hard-delete safety net beneath the app-layer soft-delete cascade.
 */
class LibraryFolderRepositoryTest :
    FunSpec({

        test("upsert inserts a new folder and publishes Created") {
            withSqlDatabase {
                // Seed the parent library so the FK constraint is satisfied.
                sql.seedTestLibraryAndFolder(libraryId = "lib1", folderId = "seed-folder")
                val bus = ChangeBus()
                val repo = LibraryFolderRepository(db = sql, bus = bus, registry = SyncRegistry(), driver = driver)
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
            withSqlDatabase {
                sql.seedTestLibraryAndFolder(libraryId = "lib1", folderId = "seed-folder")
                val bus = ChangeBus()
                val repo = LibraryFolderRepository(db = sql, bus = bus, registry = SyncRegistry(), driver = driver)
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
            withSqlDatabase {
                sql.seedTestLibraryAndFolder(libraryId = "lib1", folderId = "seed-folder")
                val bus = ChangeBus()
                val repo = LibraryFolderRepository(db = sql, bus = bus, registry = SyncRegistry(), driver = driver)
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
            withSqlDatabase {
                val repo = LibraryFolderRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                runTest {
                    val result = repo.softDelete(FolderId("does-not-exist"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                }
            }
        }

        test("pullSince returns only rows with revision > cursor") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder(libraryId = "lib1", folderId = "seed-folder")
                val repo = LibraryFolderRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
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
            withSqlDatabase {
                val repo = LibraryFolderRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                repo.idAsStringForTest(FolderId("abc-123")) shouldBe "abc-123"
            }
        }

        // ── library→folder enumeration + natural-key lookup ───────────────────────

        test("listByLibrary returns only the library's live folders") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder(libraryId = "libA", folderId = "seed-A", folderPath = "/seedA")
                sql.seedTestLibraryAndFolder(libraryId = "libB", folderId = "seed-B", folderPath = "/seedB")
                val repo = LibraryFolderRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                runTest {
                    repo.upsert(folderPayload(id = "a1", libraryId = "libA", rootPath = "/a1"))
                    repo.upsert(folderPayload(id = "a2", libraryId = "libA", rootPath = "/a2"))
                    repo.upsert(folderPayload(id = "b1", libraryId = "libB", rootPath = "/b1"))
                    // A tombstoned folder must be excluded from the enumeration.
                    repo.upsert(folderPayload(id = "a3", libraryId = "libA", rootPath = "/a3"))
                    repo.softDelete(FolderId("a3"))

                    val aFolders = repo.listByLibrary("libA").map { it.id }.toSet()
                    aFolders shouldBe setOf("seed-A", "a1", "a2")

                    val bFolders = repo.listByLibrary("libB").map { it.id }.toSet()
                    bFolders shouldBe setOf("seed-B", "b1")
                }
            }
        }

        test("findLiveByRootPath resolves the live folder at a path and ignores tombstones") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder(libraryId = "lib1", folderId = "seed-folder", folderPath = "/seed")
                val repo = LibraryFolderRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                runTest {
                    repo.upsert(folderPayload(id = "f1", libraryId = "lib1", rootPath = "/srv/audio"))

                    repo.findLiveByRootPath("/srv/audio")?.id shouldBe "f1"
                    repo.findLiveByRootPath("/no/such/path") shouldBe null

                    // After a tombstone the path is free again (partial unique index allows reuse).
                    repo.softDelete(FolderId("f1"))
                    repo.findLiveByRootPath("/srv/audio") shouldBe null
                }
            }
        }

        // ── library→folder cascade: the FK hard-delete safety net ─────────────────

        test("hard-deleting a library cascades to its folder rows (ON DELETE CASCADE)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder(libraryId = "lib1", folderId = "seed-folder", folderPath = "/seed")
                val repo = LibraryFolderRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry(), driver = driver)
                runTest {
                    repo.upsert(folderPayload(id = "f1", libraryId = "lib1", rootPath = "/srv/audio"))
                    repo.listByLibrary("lib1").map { it.id }.toSet() shouldBe setOf("seed-folder", "f1")

                    // The hard-delete safety net beneath the app-layer soft-delete cascade:
                    // PRAGMA foreign_keys=ON makes the FK ON DELETE CASCADE remove the folders.
                    // Re-assert the pragma on this connection immediately before the delete —
                    // it is connection-scoped and must be active when the cascade fires.
                    driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
                    sql.transaction {
                        sql.librariesQueries.deleteById("lib1")
                    }

                    repo.listByLibrary("lib1").shouldBeEmpty()
                }
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
