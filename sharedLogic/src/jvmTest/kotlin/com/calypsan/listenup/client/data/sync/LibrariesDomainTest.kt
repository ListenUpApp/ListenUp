package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.LibrarySyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.librariesDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class LibrariesDomainTest :
    FunSpec({

        test("a Created event inserts the library row") {
            withHandler { handler, db ->
                handler
                    .onEvent(created(payload("lib1", "My Library")), isOwnEcho = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.libraryDao().findById("lib1")
                row shouldNotBe null
                row!!.name shouldBe "My Library"
                row.revision shouldBe 1L
                row.deletedAt shouldBe null
            }
        }

        test("an Updated event overwrites the library row") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("lib1", "Old Name")), isOwnEcho = false)
                handler.onEvent(
                    SyncEvent.Updated(
                        id = "lib1",
                        revision = 2L,
                        occurredAt = 200L,
                        clientOpId = null,
                        payload = payload("lib1", "New Name", revision = 2L),
                    ),
                    isOwnEcho = false,
                )
                val row = db.libraryDao().findById("lib1")!!
                row.name shouldBe "New Name"
                row.revision shouldBe 2L
            }
        }

        test("a Deleted event soft-deletes the library row") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("lib1", "My Library")), isOwnEcho = false)
                handler
                    .onEvent(
                        SyncEvent.Deleted(id = "lib1", revision = 5L, occurredAt = 999L, clientOpId = null),
                        isOwnEcho = false,
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.libraryDao().findById("lib1") shouldBe null
                // findAll includes tombstones
                val tombstone = db.libraryDao().findAll().first { it.id == "lib1" }
                tombstone.deletedAt shouldBe 999L
                tombstone.revision shouldBe 5L
            }
        }

        test("tombstoned row is EXCLUDED from digestRows — the digest counts live rows only (F1)") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("lib1", "My Library")), isOwnEcho = false)
                handler.onEvent(
                    SyncEvent.Deleted(id = "lib1", revision = 5L, occurredAt = 999L, clientOpId = null),
                    isOwnEcho = false,
                )
                db.libraryDao().findById("lib1") shouldBe null
                db.libraryDao().digestRows(Long.MAX_VALUE).map { it.id } shouldNotContain "lib1"
            }
        }

        test("onCatchUpItem with isTombstone = false inserts the library row") {
            withHandler { handler, db ->
                handler
                    .onCatchUpItem(payload("lib1", "My Library"), isTombstone = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row = db.libraryDao().findById("lib1")
                row shouldNotBe null
                row!!.name shouldBe "My Library"
            }
        }

        test("onCatchUpItem with isTombstone = true soft-deletes the library row") {
            withHandler { handler, db ->
                handler.onCatchUpItem(payload("lib1", "My Library"), isTombstone = false)
                handler
                    .onCatchUpItem(
                        payload("lib1", "My Library", revision = 3L, deletedAt = 500L),
                        isTombstone = true,
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()
                db.libraryDao().findById("lib1") shouldBe null
                val tombstone = db.libraryDao().findAll().first { it.id == "lib1" }
                tombstone.deletedAt shouldBe 500L
            }
        }

        test("handler self-registers under domainName 'libraries'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler = librariesDomain(db).toHandler(RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "libraries"
                registry.lookup("libraries") shouldBe handler
            } finally {
                db.close()
            }
        }
    })

private fun withHandler(block: suspend (SyncDomainHandler<LibrarySyncPayload>, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(librariesDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
        } finally {
            db.close()
        }
    }

private fun created(p: LibrarySyncPayload) =
    SyncEvent.Created(
        id = p.id,
        revision = p.revision,
        occurredAt = p.updatedAt,
        clientOpId = null,
        payload = p,
    )

private fun payload(
    id: String,
    name: String,
    revision: Long = 1L,
    deletedAt: Long? = null,
) = LibrarySyncPayload(
    id = id,
    name = name,
    metadataPrecedence = "embedded,abs",
    accessMode = "open",
    createdByUserId = null,
    revision = revision,
    updatedAt = 100L,
    createdAt = 1L,
    deletedAt = deletedAt,
    initialScanCompletedAt = null,
)
