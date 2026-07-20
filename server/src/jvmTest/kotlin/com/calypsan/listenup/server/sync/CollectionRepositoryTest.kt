@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Tests for [CollectionRepository], [CollectionBookRepository], and [CollectionGrantRepository].
 *
 * All tests use a real in-memory database with fully-satisfied FK constraints:
 * library + folder rows are seeded via [seedTestLibraryAndFolder]; book rows via
 * [seedTestBook]. CollectionsTable.ownerId is a plain text column (no FK), so
 * no user seeding is required. CollectionGrantsTable.principalId and
 * grantedByUserId are also plain text columns, so grant tests need no user rows.
 */
class CollectionRepositoryTest :
    FunSpec({

        // ── CollectionRepository: upsert + findById ───────────────────────────────

        test("collection upsert publishes Created with bumped revision and correct timestamps") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val fixedTime = Instant.fromEpochMilliseconds(1_730_000_000_000L)
                val repo =
                    CollectionRepository(
                        db = sql,
                        bus = bus,
                        registry = SyncRegistry(),
                        driver = driver,
                        clock =
                            object : Clock {
                                override fun now() = fixedTime
                            },
                    )

                runTest {
                    val deferredBusEvent = async { bus.subscribe().first() }
                    advanceUntilIdle()

                    val payload =
                        CollectionSyncPayload(
                            id = "col1",
                            libraryId = "test-library",
                            ownerId = "user1",
                            name = "My Collection",
                            revision = 0L,
                            updatedAt = 0L,
                        )
                    val result = repo.upsert(payload, clientOpId = "op-1")

                    result.shouldBeInstanceOf<AppResult.Success<CollectionSyncPayload>>()
                    val saved = (result as AppResult.Success).data
                    saved.id shouldBe "col1"
                    saved.name shouldBe "My Collection"
                    saved.revision shouldBe 1L
                    saved.updatedAt shouldBe 1_730_000_000_000L
                    saved.deletedAt shouldBe null

                    val busEvent = deferredBusEvent.await()
                    busEvent.repo.domainName shouldBe "collections"
                    val event = busEvent.event
                    event.shouldBeInstanceOf<SyncEvent.Created<CollectionSyncPayload>>()
                    event.id shouldBe "col1"
                    event.revision shouldBe 1L
                    event.clientOpId shouldBe "op-1"
                    (event as SyncEvent.Created<*>).payload shouldBe saved
                }
            }
        }

        test("findById returns the collection after upsert") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo =
                    CollectionRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        driver = driver,
                    )

                runTest {
                    val payload =
                        CollectionSyncPayload(
                            id = "col1",
                            libraryId = "test-library",
                            ownerId = "user1",
                            name = "My Collection",
                            revision = 0L,
                            updatedAt = 0L,
                        )
                    repo.upsert(payload)

                    val found = repo.findById("col1")
                    found.shouldNotBeNull()
                    found.id shouldBe "col1"
                    found.name shouldBe "My Collection"
                    found.deletedAt shouldBe null
                }
            }
        }

        test("findById returns null for a soft-deleted collection") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo =
                    CollectionRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        driver = driver,
                    )

                runTest {
                    val payload =
                        CollectionSyncPayload(
                            id = "col1",
                            libraryId = "test-library",
                            ownerId = "user1",
                            name = "My Collection",
                            revision = 0L,
                            updatedAt = 0L,
                        )
                    repo.upsert(payload)
                    repo.softDelete("col1")

                    repo.findById("col1").shouldBeNull()
                }
            }
        }

        // ── CollectionRepository: findInboxForLibrary ─────────────────────────────

        test("findInboxForLibrary returns only the inbox collection") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo =
                    CollectionRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        driver = driver,
                    )

                runTest {
                    // Seed a regular collection and the inbox; stamp type='INBOX' since
                    // the payload.isInbox field is no longer written as a storage column —
                    // findInboxForLibrary now queries type = 'INBOX'.
                    repo.upsert(
                        CollectionSyncPayload(
                            id = "col-regular",
                            libraryId = "test-library",
                            ownerId = "user1",
                            name = "Regular",
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    repo.upsert(
                        CollectionSyncPayload(
                            id = "col-inbox",
                            libraryId = "test-library",
                            ownerId = "user1",
                            name = "Inbox",
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    repo.setType("col-inbox", "INBOX")

                    val inbox = repo.findInboxForLibrary("test-library")
                    inbox.shouldNotBeNull()
                    inbox.id shouldBe "col-inbox"
                    inbox.isInbox shouldBe true
                }
            }
        }

        test("findInboxForLibrary returns null when no inbox exists") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo =
                    CollectionRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        driver = driver,
                    )

                runTest {
                    repo.findInboxForLibrary("test-library").shouldBeNull()
                }
            }
        }

        // ── CollectionRepository: listOwnedBy / listAll ───────────────────────────

        test("listOwnedBy returns only collections owned by the given user") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo =
                    CollectionRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        driver = driver,
                    )

                runTest {
                    repo.upsert(
                        CollectionSyncPayload(
                            id = "col1",
                            libraryId = "test-library",
                            ownerId = "user1",
                            name = "A",
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    repo.upsert(
                        CollectionSyncPayload(
                            id = "col2",
                            libraryId = "test-library",
                            ownerId = "user2",
                            name = "B",
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )

                    val user1Collections = repo.listOwnedBy("user1")
                    user1Collections shouldHaveSize 1
                    user1Collections.first().id shouldBe "col1"
                }
            }
        }

        test("listAll returns all non-deleted collections") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                val repo =
                    CollectionRepository(
                        db = sql,
                        bus = ChangeBus(),
                        registry = SyncRegistry(),
                        driver = driver,
                    )

                runTest {
                    repo.upsert(
                        CollectionSyncPayload(
                            id = "col1",
                            libraryId = "test-library",
                            ownerId = "user1",
                            name = "A",
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    repo.upsert(
                        CollectionSyncPayload(
                            id = "col2",
                            libraryId = "test-library",
                            ownerId = "user2",
                            name = "B",
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    repo.softDelete("col2")

                    val all = repo.listAll()
                    all shouldHaveSize 1
                    all.first().id shouldBe "col1"
                }
            }
        }

        // ── CollectionBookRepository: upsert + findBookIdsForCollection ───────────

        test("junction upsert and findBookIdsForCollection returns the book") {
            withSqlDatabase {

                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val collectionRepo =
                    CollectionRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    )
                val junctionRepo =
                    CollectionBookRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    )

                runTest {
                    collectionRepo.upsert(
                        CollectionSyncPayload(
                            id = "col1",
                            libraryId = "test-library",
                            ownerId = "user1",
                            name = "A",
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    junctionRepo.upsert(
                        CollectionBookSyncPayload(
                            id = "col1:book1",
                            collectionId = "col1",
                            bookId = "book1",
                            createdAt = 1000L,
                            revision = 0L,
                        ),
                    )

                    val bookIds = junctionRepo.findBookIdsForCollection("col1")
                    bookIds shouldHaveSize 1
                    bookIds.first() shouldBe "book1"
                }
            }
        }

        test("junction softDelete excludes the row from findBookIdsForCollection") {
            withSqlDatabase {

                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val collectionRepo =
                    CollectionRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    )
                val junctionRepo =
                    CollectionBookRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    )

                runTest {
                    collectionRepo.upsert(
                        CollectionSyncPayload(
                            id = "col1",
                            libraryId = "test-library",
                            ownerId = "user1",
                            name = "A",
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    junctionRepo.upsert(
                        CollectionBookSyncPayload(
                            id = "col1:book1",
                            collectionId = "col1",
                            bookId = "book1",
                            createdAt = 1000L,
                            revision = 0L,
                        ),
                    )
                    junctionRepo.softDelete("col1", "book1")

                    junctionRepo.findBookIdsForCollection("col1").shouldBeEmpty()
                }
            }
        }

        test("countLiveForCollection returns correct count") {
            withSqlDatabase {

                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val collectionRepo =
                    CollectionRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    )
                val junctionRepo =
                    CollectionBookRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    )

                runTest {
                    collectionRepo.upsert(
                        CollectionSyncPayload(
                            id = "col1",
                            libraryId = "test-library",
                            ownerId = "user1",
                            name = "A",
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    junctionRepo.upsert(
                        CollectionBookSyncPayload("col1:book1", "col1", "book1", 1000L, 0L),
                    )
                    junctionRepo.upsert(
                        CollectionBookSyncPayload("col1:book2", "col1", "book2", 2000L, 0L),
                    )

                    junctionRepo.countLiveForCollection("col1") shouldBe 2L

                    junctionRepo.softDelete("col1", "book1")
                    junctionRepo.countLiveForCollection("col1") shouldBe 1L
                }
            }
        }

        test("softDeleteAllForCollection tombstones all junction rows") {
            withSqlDatabase {

                sql.seedTestLibraryAndFolder()
                sql.seedTestBook("book1")
                sql.seedTestBook("book2")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val collectionRepo =
                    CollectionRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    )
                val junctionRepo =
                    CollectionBookRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    )

                runTest {
                    collectionRepo.upsert(
                        CollectionSyncPayload(
                            id = "col1",
                            libraryId = "test-library",
                            ownerId = "user1",
                            name = "A",
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    junctionRepo.upsert(CollectionBookSyncPayload("col1:book1", "col1", "book1", 1000L, 0L))
                    junctionRepo.upsert(CollectionBookSyncPayload("col1:book2", "col1", "book2", 2000L, 0L))

                    val count = junctionRepo.softDeleteAllForCollection("col1")
                    count shouldBe 2

                    junctionRepo.findBookIdsForCollection("col1").shouldBeEmpty()
                }
            }
        }

        // ── CollectionGrantRepository: upsert + findActiveGrant ───────────────────

        test("share upsert and findActiveGrant returns the share") {
            withSqlDatabase {

                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("user2")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val collectionRepo =
                    CollectionRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    )
                val grantRepo =
                    CollectionGrantRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    )

                runTest {
                    collectionRepo.upsert(
                        CollectionSyncPayload(
                            id = "col1",
                            libraryId = "test-library",
                            ownerId = "user1",
                            name = "Shared",
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    grantRepo.upsert(
                        CollectionShareSyncPayload(
                            id = "share1",
                            collectionId = "col1",
                            sharedWithUserId = "user2",
                            sharedByUserId = "user1",
                            permission = SharePermission.Read,
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )

                    val share = grantRepo.findActiveGrant("col1", "user2")
                    share.shouldNotBeNull()
                    share.id shouldBe "share1"
                    share.collectionId shouldBe "col1"
                    share.sharedWithUserId shouldBe "user2"
                }
            }
        }

        test("softDeleteGrant makes findActiveGrant return null") {
            withSqlDatabase {

                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("user2")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val collectionRepo =
                    CollectionRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    )
                val grantRepo =
                    CollectionGrantRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    )

                runTest {
                    collectionRepo.upsert(
                        CollectionSyncPayload(
                            id = "col1",
                            libraryId = "test-library",
                            ownerId = "user1",
                            name = "Shared",
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    grantRepo.upsert(
                        CollectionShareSyncPayload(
                            id = "share1",
                            collectionId = "col1",
                            sharedWithUserId = "user2",
                            sharedByUserId = "user1",
                            permission = SharePermission.Read,
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )

                    val result = grantRepo.softDeleteGrant("col1", "user2")
                    result.shouldBeInstanceOf<AppResult.Success<Unit>>()

                    grantRepo.findActiveGrant("col1", "user2").shouldBeNull()
                }
            }
        }

        test("permission round-trips — Write share reads back as Write") {
            withSqlDatabase {

                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("user2")
                val bus = ChangeBus()
                val registry = SyncRegistry()
                val collectionRepo =
                    CollectionRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    )
                val grantRepo =
                    CollectionGrantRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    )

                runTest {
                    collectionRepo.upsert(
                        CollectionSyncPayload(
                            id = "col1",
                            libraryId = "test-library",
                            ownerId = "user1",
                            name = "Shared",
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )
                    grantRepo.upsert(
                        CollectionShareSyncPayload(
                            id = "share1",
                            collectionId = "col1",
                            sharedWithUserId = "user2",
                            sharedByUserId = "user1",
                            permission = SharePermission.Write,
                            revision = 0L,
                            updatedAt = 0L,
                        ),
                    )

                    val share = grantRepo.findActiveGrant("col1", "user2")
                    share.shouldNotBeNull()
                    share.permission shouldBe SharePermission.Write
                }
            }
        }

        test("softDeleteGrant returns Failure when no active share exists") {
            withSqlDatabase {

                sql.seedTestLibraryAndFolder()
                val bus = ChangeBus()
                val registry = SyncRegistry()
                CollectionRepository(
                    db = sql,
                    bus = bus,
                    registry = registry,
                    driver = driver,
                )
                val grantRepo =
                    CollectionGrantRepository(
                        db = sql,
                        bus = bus,
                        registry = registry,
                        driver = driver,
                    )

                runTest {
                    val result = grantRepo.softDeleteGrant("col-none", "user-none")
                    result.shouldBeInstanceOf<AppResult.Failure>()
                }
            }
        }
    })
