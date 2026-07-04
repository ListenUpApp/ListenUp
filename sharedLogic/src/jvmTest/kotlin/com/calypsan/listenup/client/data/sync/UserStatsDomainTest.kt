package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.UserStatsSyncPayload
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.data.sync.domains.userStatsDomain
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class UserStatsDomainTest :
    FunSpec({

        test("Created event upserts a new user_stats row matching the payload") {
            withHandler { handler, db ->
                handler
                    .onEvent(created(payload("user-1")), isOwnEcho = false)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                val row = db.userStatsDao().getForUser("user-1")
                row shouldNotBe null
                row!!.id shouldBe "user-1"
                row.totalSecondsAllTime shouldBe 1000L
                row.totalSecondsLast7Days shouldBe 200L
                row.totalSecondsLast30Days shouldBe 500L
                row.booksStarted shouldBe 3
                row.booksFinished shouldBe 1
                row.currentStreakDays shouldBe 5
                row.longestStreakDays shouldBe 10
                row.lastEventDate shouldBe "2026-05-22"
                row.revision shouldBe 1L
                row.deletedAt shouldBe null
            }
        }

        test("Updated event for an existing user REPLACES the local row entirely") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("user-1", totalSecondsAllTime = 1000L, revision = 1L)), isOwnEcho = false)

                handler
                    .onEvent(
                        updated(
                            payload(
                                "user-1",
                                totalSecondsAllTime = 9999L,
                                booksStarted = 99,
                                currentStreakDays = 42,
                                revision = 2L,
                            ),
                        ),
                        isOwnEcho = false,
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                val row = db.userStatsDao().getForUser("user-1").shouldNotBeNull()
                // Server's materialized view wins — all fields replaced
                row.totalSecondsAllTime shouldBe 9999L
                row.booksStarted shouldBe 99
                row.currentStreakDays shouldBe 42
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

                val row = db.userStatsDao().getForUser("user-2").shouldNotBeNull()
                row.totalSecondsAllTime shouldBe 5000L
            }
        }

        test("onCatchUpItem for an existing row also replaces it (server wins)") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("user-3", totalSecondsAllTime = 100L, revision = 1L)), isOwnEcho = false)
                handler.onCatchUpItem(payload("user-3", totalSecondsAllTime = 7777L, revision = 3L), isTombstone = false)

                val row = db.userStatsDao().getForUser("user-3").shouldNotBeNull()
                row.totalSecondsAllTime shouldBe 7777L
                row.revision shouldBe 3L
            }
        }

        test("tombstoned row is EXCLUDED from digestRows — the digest counts live rows only (F1)") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("user-tomb")), isOwnEcho = false)
                handler.onCatchUpItem(
                    payload("user-tomb", revision = 2L, deletedAt = 999_000L),
                    isTombstone = true,
                )
                // observeAll filters tombstones — invisible to reads
                db
                    .userStatsDao()
                    .observeAll()
                    .first()
                    .none { it.id == "user-tomb" } shouldBe true
                // and EXCLUDED from the digest — the digest counts live rows only, so a client that
                // tombstoned this row locally converges (F1). Deletions still reach clients via the
                // firehose and the tombstone-ungated access-filtered catch-up. Digest-drift reconciliation
                db.userStatsDao().digestRows(Long.MAX_VALUE).map { it.id } shouldNotContain "user-tomb"
            }
        }

        test("handler self-registers under domainName 'user_stats'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler = userStatsDomain(db).toHandler(RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "user_stats"
                registry.lookup("user_stats") shouldBe handler
            } finally {
                db.close()
            }
        }
    })

// ── Fixtures ─────────────────────────────────────────────────────────────────

private fun withHandler(block: suspend (SyncDomainHandler<UserStatsSyncPayload>, ListenUpDatabase) -> Unit) =
    runTest {
        val db = createInMemoryTestDatabase()
        try {
            block(userStatsDomain(db).toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry()), db)
        } finally {
            db.close()
        }
    }

private fun created(p: UserStatsSyncPayload) =
    SyncEvent.Created(
        id = p.id,
        revision = p.revision,
        occurredAt = p.updatedAt,
        clientOpId = null,
        payload = p,
    )

private fun updated(p: UserStatsSyncPayload) =
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
    booksStarted: Int = 3,
    booksFinished: Int = 1,
    currentStreakDays: Int = 5,
    longestStreakDays: Int = 10,
    lastEventDate: String? = "2026-05-22",
    revision: Long = 1L,
    deletedAt: Long? = null,
) = UserStatsSyncPayload(
    id = id,
    totalSecondsAllTime = totalSecondsAllTime,
    totalSecondsLast7Days = totalSecondsLast7Days,
    totalSecondsLast30Days = totalSecondsLast30Days,
    booksStarted = booksStarted,
    booksFinished = booksFinished,
    currentStreakDays = currentStreakDays,
    longestStreakDays = longestStreakDays,
    lastEventDate = lastEventDate,
    revision = revision,
    updatedAt = 200L,
    createdAt = 50L,
    deletedAt = deletedAt,
)
