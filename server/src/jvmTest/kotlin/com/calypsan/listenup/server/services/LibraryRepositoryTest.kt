@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.LibrarySyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Tests for [LibraryRepository] — a global (cross-user) SQLDelight aggregate.
 *
 * Every test runs against a real migrated database exposed as a SQLDelight
 * [ListenUpDatabase] (`sql`, the repo's view) over one file (see
 * [com.calypsan.listenup.server.testing.withSqlDatabase]).
 *
 * Coverage: the base substrate path (create/update/delete + pull cursor) plus the
 * load-bearing **off-payload `inbox_enabled` gate**: a syncable upsert never clobbers
 * it, [LibraryRepository.setInboxEnabled] flips it and publishes an Updated event, and
 * [LibraryRepository.readInboxEnabled] round-trips it.
 */
class LibraryRepositoryTest :
    FunSpec({

        test("upsert inserts a new library and publishes Created") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = LibraryRepository(db = sql, bus = bus, registry = SyncRegistry())
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
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = LibraryRepository(db = sql, bus = bus, registry = SyncRegistry())
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
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = LibraryRepository(db = sql, bus = bus, registry = SyncRegistry())
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
            withSqlDatabase {
                val repo = LibraryRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    val result = repo.softDelete(LibraryId("does-not-exist"))
                    result.shouldBeInstanceOf<AppResult.Failure>()
                }
            }
        }

        test("pullSince returns only rows with revision > cursor") {
            withSqlDatabase {
                val repo = LibraryRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
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
            withSqlDatabase {
                val repo = LibraryRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                repo.idAsStringForTest(LibraryId("abc-123")) shouldBe "abc-123"
            }
        }

        // ── inbox_enabled: the off-payload server-side gate ───────────────────────

        test("inbox_enabled defaults to false and round-trips through set/read") {
            withSqlDatabase {
                val repo = LibraryRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(libraryPayload(id = "lib1", name = "Lib"))

                    // Default off — sharing is the default.
                    repo.readInboxEnabled(LibraryId("lib1")) shouldBe false

                    repo
                        .setInboxEnabled(LibraryId("lib1"), enabled = true)
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                    repo.readInboxEnabled(LibraryId("lib1")) shouldBe true

                    repo
                        .setInboxEnabled(LibraryId("lib1"), enabled = false)
                        .shouldBeInstanceOf<AppResult.Success<Unit>>()
                    repo.readInboxEnabled(LibraryId("lib1")) shouldBe false
                }
            }
        }

        test("setInboxEnabled bumps revision and publishes Updated") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = LibraryRepository(db = sql, bus = bus, registry = SyncRegistry())
                runTest {
                    // Drop the Created event, await the Updated the gate-flip emits.
                    val deferred = async { bus.subscribe().drop(1).first() }
                    advanceUntilIdle()

                    val created = repo.upsert(libraryPayload(id = "lib1", name = "Lib")) as AppResult.Success
                    repo.setInboxEnabled(LibraryId("lib1"), enabled = true)

                    val busEvent = deferred.await()
                    busEvent.event.shouldBeInstanceOf<SyncEvent.Updated<LibrarySyncPayload>>()
                    busEvent.event.id shouldBe "lib1"
                    // The gate-flip bumped revision past the create revision.
                    (busEvent.event.revision > created.data.revision) shouldBe true
                }
            }
        }

        test("setInboxEnabled does not clobber the synced library fields") {
            withSqlDatabase {
                val repo = LibraryRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(libraryPayload(id = "lib1", name = "My Library", accessMode = "private"))
                    repo.setInboxEnabled(LibraryId("lib1"), enabled = true)

                    // The off-payload gate write must leave name/accessMode untouched.
                    val page = repo.pullSince(userId = null, cursor = 0L, limit = 100)
                    val library = page.items.first { it.id == "lib1" }
                    library.name shouldBe "My Library"
                    library.accessMode shouldBe "private"
                }
            }
        }

        test("a syncable upsert preserves an already-enabled inbox gate") {
            withSqlDatabase {
                val repo = LibraryRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(libraryPayload(id = "lib1", name = "Lib"))
                    repo.setInboxEnabled(LibraryId("lib1"), enabled = true)

                    // A plain rename must NOT reset the off-payload gate (the update query omits it).
                    repo.upsert(libraryPayload(id = "lib1", name = "Renamed"))
                    repo.readInboxEnabled(LibraryId("lib1")) shouldBe true
                }
            }
        }

        test("setInboxEnabled on a missing library returns Failure") {
            withSqlDatabase {
                val repo = LibraryRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo
                        .setInboxEnabled(LibraryId("does-not-exist"), enabled = true)
                        .shouldBeInstanceOf<AppResult.Failure>()
                }
            }
        }

        // ── initial_scan_completed_at: the server-authoritative first-scan gate ────

        test("markInitialScanCompleted stamps the first time, bumps revision, and publishes Updated") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = LibraryRepository(db = sql, bus = bus, registry = SyncRegistry())
                runTest {
                    val deferred = async { bus.subscribe().drop(1).first() }
                    advanceUntilIdle()

                    val created = repo.upsert(libraryPayload(id = "lib1", name = "Lib")) as AppResult.Success
                    // Default: never scanned.
                    repo.stampOf("lib1") shouldBe null

                    repo.markInitialScanCompleted(LibraryId("lib1"), completedAt = 5_000L) shouldBe true
                    repo.stampOf("lib1") shouldBe 5_000L

                    val busEvent = deferred.await()
                    busEvent.event.shouldBeInstanceOf<SyncEvent.Updated<LibrarySyncPayload>>()
                    busEvent.event.id shouldBe "lib1"
                    (busEvent.event.revision > created.data.revision) shouldBe true
                }
            }
        }

        test("markInitialScanCompleted is first-only: a second call does not overwrite and publishes nothing") {
            withSqlDatabase {
                val bus = ChangeBus()
                val repo = LibraryRepository(db = sql, bus = bus, registry = SyncRegistry())
                runTest {
                    repo.upsert(libraryPayload(id = "lib1", name = "Lib"))
                    repo.markInitialScanCompleted(LibraryId("lib1"), completedAt = 5_000L) shouldBe true

                    // A rescan of the already-stamped library writes nothing and returns false.
                    repo.markInitialScanCompleted(LibraryId("lib1"), completedAt = 9_999L) shouldBe false
                    // The original stamp is untouched — the IS NULL guard held.
                    repo.stampOf("lib1") shouldBe 5_000L
                }
            }
        }

        test("markInitialScanCompleted on a missing library returns false") {
            withSqlDatabase {
                val repo = LibraryRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.markInitialScanCompleted(LibraryId("does-not-exist"), completedAt = 1L) shouldBe false
                }
            }
        }

        test("a syncable upsert preserves an already-stamped initial-scan completion") {
            withSqlDatabase {
                val repo = LibraryRepository(db = sql, bus = ChangeBus(), registry = SyncRegistry())
                runTest {
                    repo.upsert(libraryPayload(id = "lib1", name = "Lib"))
                    repo.markInitialScanCompleted(LibraryId("lib1"), completedAt = 5_000L)

                    // A plain rename must NOT reset the off-payload stamp (the update query omits it).
                    repo.upsert(libraryPayload(id = "lib1", name = "Renamed"))
                    repo.stampOf("lib1") shouldBe 5_000L
                }
            }
        }
    })

/** Reads the `initial_scan_completed_at` for [id] via the syncable read path (`readPayload` is protected). */
private suspend fun LibraryRepository.stampOf(id: String): Long? =
    pullSince(userId = null, cursor = 0L, limit = 100)
        .items
        .firstOrNull { it.id == id }
        ?.initialScanCompletedAt

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
        initialScanCompletedAt = null,
    )
