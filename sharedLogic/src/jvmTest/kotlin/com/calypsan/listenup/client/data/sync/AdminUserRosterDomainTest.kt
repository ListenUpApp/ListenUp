package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.AdminUserRosterSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.adminUserRosterDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class AdminUserRosterDomainTest :
    FunSpec({

        test("Created event upserts a new admin_user_roster row matching the payload") {
            withHandler { handler, db ->
                handler
                    .onEvent(created(payload("user-1")))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                val row =
                    db
                        .adminUserRosterDao()
                        .observeAll()
                        .first()
                        .first { it.id == "user-1" }
                row.email shouldBe "user-1@example.com"
                row.displayName shouldBe "Test User"
                row.role shouldBe "user"
                row.status shouldBe "active"
                row.canShare shouldBe true
                row.accountCreatedAt shouldBe 50L
                row.revision shouldBe 1L
            }
        }

        test("Updated event for an existing user REPLACES the local row entirely (server wins)") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("user-1", role = "user", revision = 1L)))

                handler
                    .onEvent(
                        updated(payload("user-1", role = "admin", canShare = false, revision = 2L)),
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                val row =
                    db
                        .adminUserRosterDao()
                        .observeAll()
                        .first()
                        .first { it.id == "user-1" }
                row.role shouldBe "admin"
                row.canShare shouldBe false
                row.revision shouldBe 2L
            }
        }

        test("onCatchUpItem for a row not in Room upserts it") {
            withHandler { handler, db ->
                handler
                    .onCatchUpItem(payload("user-2"), isTombstone = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                val row =
                    db
                        .adminUserRosterDao()
                        .observeAll()
                        .first()
                        .firstOrNull { it.id == "user-2" }
                row.shouldNotBeNull()
            }
        }

        test("onCatchUpItem for an existing row also replaces it (server wins)") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("user-3", status = "pending_approval", revision = 1L)))
                handler.onCatchUpItem(payload("user-3", status = "active", revision = 3L), isTombstone = false)

                val row =
                    db
                        .adminUserRosterDao()
                        .observeAll()
                        .first()
                        .first { it.id == "user-3" }
                row.status shouldBe "active"
                row.revision shouldBe 3L
            }
        }

        test("Deleted event soft-deletes the row so observeAll no longer returns it") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("user-deleted", revision = 1L)))

                handler
                    .onEvent(
                        SyncEvent.Deleted(id = "user-deleted", revision = 2L, occurredAt = 999_000L),
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                val liveRows = db.adminUserRosterDao().observeAll().first()
                liveRows.none { it.id == "user-deleted" } shouldBe true
            }
        }

        test("tombstoned row is EXCLUDED from digestRows — the digest counts live rows only (F1)") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("user-deleted", revision = 1L)))
                handler.onEvent(
                    SyncEvent.Deleted(id = "user-deleted", revision = 2L, occurredAt = 999_000L),
                )
                // observeAll filters tombstones — invisible to reads
                db
                    .adminUserRosterDao()
                    .observeAll()
                    .first()
                    .none { it.id == "user-deleted" } shouldBe true
                // and EXCLUDED from the digest — the digest counts live rows only, so a client that
                // tombstoned this row locally converges (F1). Deletions still reach clients via the
                // firehose and the tombstone-ungated access-filtered catch-up. Digest-drift reconciliation
                db.adminUserRosterDao().digestRows(Long.MAX_VALUE).map { it.id } shouldNotContain "user-deleted"
            }
        }

        test("onCatchUpItem with isTombstone=true soft-deletes the row") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("user-4", revision = 1L)))

                handler
                    .onCatchUpItem(
                        payload("user-4", revision = 2L, deletedAt = 999_000L),
                        isTombstone = true,
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                val liveRows = db.adminUserRosterDao().observeAll().first()
                liveRows.none { it.id == "user-4" } shouldBe true
            }
        }

        test("handler self-registers under domainName 'admin_user_roster'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler =
                    adminUserRosterDomain(db)
                        .toHandler(RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "admin_user_roster"
                registry.lookup("admin_user_roster") shouldBe handler
            } finally {
                db.close()
            }
        }
    })

// ── Fixtures ─────────────────────────────────────────────────────────────────

private fun withHandler(block: suspend (SyncDomainHandler<AdminUserRosterSyncPayload>, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(
                adminUserRosterDomain(db)
                    .toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry()),
                db,
            )
        } finally {
            db.close()
        }
    }

private fun created(p: AdminUserRosterSyncPayload) =
    SyncEvent.Created(
        id = p.id,
        revision = p.revision,
        occurredAt = p.updatedAt,
        clientOpId = null,
        payload = p,
    )

private fun updated(p: AdminUserRosterSyncPayload) =
    SyncEvent.Updated(
        id = p.id,
        revision = p.revision,
        occurredAt = p.updatedAt,
        clientOpId = null,
        payload = p,
    )

private fun payload(
    id: String,
    role: String = "user",
    status: String = "active",
    canShare: Boolean = true,
    revision: Long = 1L,
    deletedAt: Long? = null,
) = AdminUserRosterSyncPayload(
    id = id,
    email = "$id@example.com",
    displayName = "Test User",
    role = role,
    status = status,
    canShare = canShare,
    accountCreatedAt = 50L,
    revision = revision,
    updatedAt = 200L,
    createdAt = 50L,
    deletedAt = deletedAt,
)
