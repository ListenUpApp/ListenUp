package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.LibraryFolderSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.entity.LibraryEntity
import com.calypsan.listenup.client.data.sync.domains.libraryFoldersDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class LibraryFoldersDomainTest :
    FunSpec({

        test("a Created event inserts the folder row") {
            withHandler { handler, db ->
                seedLibrary(db, "lib1")
                handler
                    .onEvent(created(payload("f1", "lib1", "/audiobooks")), isOwnEcho = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.libraryFolderDao().findById("f1")
                row shouldNotBe null
                row!!.rootPath shouldBe "/audiobooks"
                row.libraryId shouldBe "lib1"
                row.revision shouldBe 1L
                row.deletedAt shouldBe null
            }
        }

        test("an Updated event overwrites the folder row") {
            withHandler { handler, db ->
                seedLibrary(db, "lib1")
                handler.onEvent(created(payload("f1", "lib1", "/audiobooks")), isOwnEcho = false)
                handler.onEvent(
                    SyncEvent.Updated(
                        id = "f1",
                        revision = 2L,
                        occurredAt = 200L,
                        clientOpId = null,
                        payload = payload("f1", "lib1", "/books", revision = 2L),
                    ),
                    isOwnEcho = false,
                )
                val row = db.libraryFolderDao().findById("f1")!!
                row.rootPath shouldBe "/books"
                row.revision shouldBe 2L
            }
        }

        test("a Deleted event soft-deletes the folder row") {
            withHandler { handler, db ->
                seedLibrary(db, "lib1")
                handler.onEvent(created(payload("f1", "lib1", "/audiobooks")), isOwnEcho = false)
                handler
                    .onEvent(
                        SyncEvent.Deleted(id = "f1", revision = 5L, occurredAt = 999L, clientOpId = null),
                        isOwnEcho = false,
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()
                // observeForLibrary excludes tombstones
                val tombstone = db.libraryFolderDao().findById("f1")!!
                tombstone.deletedAt shouldBe 999L
                tombstone.revision shouldBe 5L
            }
        }

        test("tombstoned row is EXCLUDED from digestRows — the digest counts live rows only (F1)") {
            withHandler { handler, db ->
                seedLibrary(db, "lib1")
                handler.onEvent(created(payload("f1", "lib1", "/audiobooks")), isOwnEcho = false)
                handler.onEvent(
                    SyncEvent.Deleted(id = "f1", revision = 5L, occurredAt = 999L, clientOpId = null),
                    isOwnEcho = false,
                )
                // findAllForLibrary filters tombstones — invisible to reads
                db.libraryFolderDao().findAllForLibrary("lib1").none { it.id == "f1" } shouldBe true
                // and EXCLUDED from the digest — the digest counts live rows only, so a client that
                // tombstoned this row locally converges (F1). Deletions still reach clients via the
                // firehose and the tombstone-ungated access-filtered catch-up. Digest-drift reconciliation
                db.libraryFolderDao().digestRows(Long.MAX_VALUE).map { it.id } shouldNotContain "f1"
            }
        }

        test("onCatchUpItem with isTombstone = false inserts the folder row") {
            withHandler { handler, db ->
                seedLibrary(db, "lib1")
                handler
                    .onCatchUpItem(payload("f1", "lib1", "/audiobooks"), isTombstone = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.libraryFolderDao().findById("f1")
                row shouldNotBe null
                row!!.rootPath shouldBe "/audiobooks"
            }
        }

        test("onCatchUpItem with isTombstone = true soft-deletes the folder row") {
            withHandler { handler, db ->
                seedLibrary(db, "lib1")
                handler.onCatchUpItem(payload("f1", "lib1", "/audiobooks"), isTombstone = false)
                handler
                    .onCatchUpItem(
                        payload("f1", "lib1", "/audiobooks", revision = 3L, deletedAt = 500L),
                        isTombstone = true,
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()
                val tombstone = db.libraryFolderDao().findById("f1")!!
                tombstone.deletedAt shouldBe 500L
            }
        }

        test("handler self-registers under domainName 'library_folders'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler = libraryFoldersDomain(db).toHandler(RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "library_folders"
                registry.lookup("library_folders") shouldBe handler
            } finally {
                db.close()
            }
        }
    })

private suspend fun seedLibrary(
    db: ListenUpDatabase,
    id: String,
) {
    db.libraryDao().upsert(
        LibraryEntity(
            id = id,
            name = "Test Library",
            metadataPrecedence = "embedded,abs",
            accessMode = "open",
            createdByUserId = null,
            createdAt = 1L,
            revision = 1L,
            deletedAt = null,
            initialScanCompletedAt = null,
        ),
    )
}

private fun withHandler(block: suspend (SyncDomainHandler<LibraryFolderSyncPayload>, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(libraryFoldersDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
        } finally {
            db.close()
        }
    }

private fun created(p: LibraryFolderSyncPayload) =
    SyncEvent.Created(
        id = p.id,
        revision = p.revision,
        occurredAt = p.updatedAt,
        clientOpId = null,
        payload = p,
    )

private fun payload(
    id: String,
    libraryId: String,
    rootPath: String,
    revision: Long = 1L,
    deletedAt: Long? = null,
) = LibraryFolderSyncPayload(
    id = id,
    libraryId = libraryId,
    rootPath = rootPath,
    revision = revision,
    updatedAt = 100L,
    createdAt = 1L,
    deletedAt = deletedAt,
)
