package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.notifications.NotificationEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.NotificationSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.notificationsDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Covers [com.calypsan.listenup.client.data.sync.domains.notificationsDomain]: Room write-through
 * for the `notifications` per-user inbox domain (Notifications — Room v7).
 */
class NotificationsDomainTest :
    FunSpec({

        test("Created event upserts the row with its eventJson verbatim and readAt null") {
            withHandler { handler, db ->
                val body =
                    contractJson.encodeToString(
                        NotificationEvent.serializer(),
                        NotificationEvent.RegistrationApproval(userId = "u1"),
                    )
                handler
                    .onEvent(created(payload("n1", body = body, createdAt = 100L, revision = 1L)))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row =
                    db
                        .notificationDao()
                        .observeAll()
                        .first()
                        .single()
                row.id shouldBe "n1"
                row.type shouldBe "registration_approval"
                row.eventJson shouldBe body
                row.createdAt shouldBe 100L
                row.readAt shouldBe null
                row.revision shouldBe 1L
                row.deletedAt shouldBe null
            }
        }

        test("Updated event with readAt set overwrites the local row (server-wins read state)") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("n1", revision = 1L, readAt = null)))
                handler.onEvent(
                    SyncEvent.Updated(
                        id = "n1",
                        revision = 2L,
                        occurredAt = 600L,
                        clientOpId = null,
                        payload = payload("n1", revision = 2L, readAt = 550L, updatedAt = 600L),
                    ),
                )
                val row =
                    db
                        .notificationDao()
                        .observeAll()
                        .first()
                        .single()
                row.readAt shouldBe 550L
                row.revision shouldBe 2L
            }
        }

        test("Deleted event tombstones by id; unknown id is a graceful no-op") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("n1", revision = 1L)))

                handler
                    .onEvent(SyncEvent.Deleted(id = "n1", revision = 2L, occurredAt = 800L))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                db
                    .notificationDao()
                    .observeAll()
                    .first()
                    .map { it.id } shouldNotContain "n1"
                db.notificationDao().revisionOf("n1") shouldBe 2L

                // An id that matches no local row must log and return Success without side effects.
                handler
                    .onEvent(SyncEvent.Deleted(id = "never-seen", revision = 1L, occurredAt = 900L))
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
            }
        }

        test("an UNKNOWN event type applies cleanly — type and eventJson stored verbatim (forward-compat pin)") {
            withHandler { handler, db ->
                // A type this client build has never heard of: the domain must store it untouched
                // (never decode eagerly), so the UI can render it generically and a re-sync
                // round-trips the body byte-for-byte.
                val unknownBody = """{"type":"books_added","count":37}"""
                handler
                    .onEvent(
                        created(payload("n9", type = "books_added", body = unknownBody, revision = 1L)),
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()
                val row =
                    db
                        .notificationDao()
                        .observeAll()
                        .first()
                        .single()
                row.id shouldBe "n9"
                row.type shouldBe "books_added"
                row.eventJson shouldBe unknownBody
                row.deletedAt shouldBe null
            }
        }

        test("digest participation matches the DAO — live rows only, tombstoned excluded") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("n1", revision = 1L)))
                handler.onEvent(created(payload("n2", revision = 2L)))
                handler.onEvent(SyncEvent.Deleted(id = "n2", revision = 3L, occurredAt = 800L))

                val digestIds = db.notificationDao().digestRows(Long.MAX_VALUE).map { it.id }
                digestIds shouldContain "n1"
                digestIds shouldNotContain "n2"
            }
        }

        test("handler self-registers under domainName 'notifications'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler = notificationsDomain(db).toHandler(RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "notifications"
                registry.lookup("notifications") shouldBe handler
            } finally {
                db.close()
            }
        }
    })

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun withHandler(block: suspend (SyncDomainHandler<NotificationSyncPayload>, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(notificationsDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
        } finally {
            db.close()
        }
    }

private fun payload(
    id: String,
    type: String = "registration_approval",
    body: String = """{"type":"registration_approval","userId":"u1"}""",
    createdAt: Long = 100L,
    updatedAt: Long = 100L,
    readAt: Long? = null,
    revision: Long = 1L,
    deletedAt: Long? = null,
) = NotificationSyncPayload(
    id = id,
    type = type,
    body = body,
    createdAt = createdAt,
    updatedAt = updatedAt,
    readAt = readAt,
    revision = revision,
    deletedAt = deletedAt,
)

private fun created(payload: NotificationSyncPayload) =
    SyncEvent.Created(
        id = payload.id,
        revision = payload.revision,
        occurredAt = payload.createdAt,
        clientOpId = null,
        payload = payload,
    )
