package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.PublicProfileSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.handlers.PublicProfileSyncDomainHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class PublicProfileSyncDomainHandlerTest :
    FunSpec({

        test("Created event upserts a new public_profiles row matching the payload") {
            withHandler { handler, db ->
                handler
                    .onEvent(created(payload("user-1")), isOwnEcho = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                val rows = db.publicProfileDao().digestRows(Long.MAX_VALUE)
                rows shouldNotBe emptyList<Any>()
                val row = rows.first()
                row.id shouldBe "user-1"
                row.revision shouldBe 1L
            }
        }

        test("Updated event for an existing user REPLACES the local row entirely (server wins)") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("user-1", totalSecondsAllTime = 1000L, revision = 1L)), isOwnEcho = false)

                handler
                    .onEvent(
                        updated(
                            payload(
                                "user-1",
                                totalSecondsAllTime = 9999L,
                                booksFinished = 42,
                                currentStreakDays = 7,
                                revision = 2L,
                            ),
                        ),
                        isOwnEcho = false,
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                val rows = db.publicProfileDao().digestRows(Long.MAX_VALUE)
                val row = rows.first { it.id == "user-1" }
                row.revision shouldBe 2L
            }
        }

        test("onCatchUpItem for a row not in Room upserts it") {
            withHandler { handler, db ->
                handler
                    .onCatchUpItem(
                        payload("user-2", totalSecondsAllTime = 5000L),
                        isTombstone = false,
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                val rows = db.publicProfileDao().digestRows(Long.MAX_VALUE)
                rows.any { it.id == "user-2" } shouldBe true
            }
        }

        test("onCatchUpItem for an existing row also replaces it (server wins)") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("user-3", revision = 1L)), isOwnEcho = false)
                handler.onCatchUpItem(payload("user-3", revision = 3L), isTombstone = false)

                val rows = db.publicProfileDao().digestRows(Long.MAX_VALUE)
                val row = rows.first { it.id == "user-3" }
                row.revision shouldBe 3L
            }
        }

        test("Deleted event soft-deletes the row so observeAll no longer returns it") {
            withHandler { handler, db ->
                // Seed a live row via a Created event
                handler.onEvent(created(payload("user-deleted", revision = 1L)), isOwnEcho = false)

                // Apply a live Deleted event — this was previously a no-op (the bug)
                handler
                    .onEvent(
                        SyncEvent.Deleted(id = "user-deleted", revision = 2L, occurredAt = 999_000L),
                        isOwnEcho = false,
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                // digestRows includes all rows; the tombstoned row must carry the updated revision
                val allRows = db.publicProfileDao().digestRows(Long.MAX_VALUE)
                val row = allRows.first { it.id == "user-deleted" }
                row.revision shouldBe 2L
                // observeAll() only emits live rows; confirm the row is absent there
                // (collect one emission — we're inside runTest so the Room Flow is synchronous)
                val liveRows = db.publicProfileDao().observeAll().first()
                liveRows.none { it.id == "user-deleted" } shouldBe true
            }
        }

        test("onCatchUpItem with isTombstone=true soft-deletes the row") {
            withHandler { handler, db ->
                // First, upsert a live row
                handler.onEvent(created(payload("user-4", revision = 1L)), isOwnEcho = false)

                // Then receive a tombstone
                handler
                    .onCatchUpItem(
                        payload("user-4", revision = 2L, deletedAt = 999_000L),
                        isTombstone = true,
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                // observeAll excludes tombstoned rows; digestRows includes them
                val allRows = db.publicProfileDao().digestRows(Long.MAX_VALUE)
                val row = allRows.first { it.id == "user-4" }
                row.revision shouldBe 2L
            }
        }

        test("handler self-registers under domainName 'public_profiles'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler = PublicProfileSyncDomainHandler(db, RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "public_profiles"
                registry.lookup("public_profiles") shouldBe handler
            } finally {
                db.close()
            }
        }
    })

// ── Fixtures ─────────────────────────────────────────────────────────────────

private fun withHandler(block: suspend (PublicProfileSyncDomainHandler, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(PublicProfileSyncDomainHandler(db, RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
        } finally {
            db.close()
        }
    }

private fun created(p: PublicProfileSyncPayload) =
    SyncEvent.Created(
        id = p.id,
        revision = p.revision,
        occurredAt = p.updatedAt,
        clientOpId = null,
        payload = p,
    )

private fun updated(p: PublicProfileSyncPayload) =
    SyncEvent.Updated(
        id = p.id,
        revision = p.revision,
        occurredAt = p.updatedAt,
        clientOpId = null,
        payload = p,
    )

private fun payload(
    id: String,
    totalSecondsAllTime: Long = 1000L,
    totalSecondsLast7Days: Long = 200L,
    totalSecondsLast30Days: Long = 500L,
    totalSecondsLast365Days: Long = 3600L,
    booksFinished: Int = 1,
    currentStreakDays: Int = 5,
    longestStreakDays: Int = 10,
    revision: Long = 1L,
    deletedAt: Long? = null,
) = PublicProfileSyncPayload(
    id = id,
    displayName = "Test User",
    avatarType = "auto",
    totalSecondsAllTime = totalSecondsAllTime,
    totalSecondsLast7Days = totalSecondsLast7Days,
    totalSecondsLast30Days = totalSecondsLast30Days,
    totalSecondsLast365Days = totalSecondsLast365Days,
    booksFinished = booksFinished,
    currentStreakDays = currentStreakDays,
    longestStreakDays = longestStreakDays,
    revision = revision,
    updatedAt = 200L,
    createdAt = 50L,
    deletedAt = deletedAt,
)
