package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.PublicProfileSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.sync.domains.publicProfilesDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.domain.repository.AvatarDownloadRepository
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class PublicProfilesDomainTest :
    FunSpec({

        test("Created event upserts a new public_profiles row matching the payload") {
            withHandler { handler, db ->
                handler
                    .onEvent(created(payload("user-1")))
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
                handler.onEvent(created(payload("user-1", totalSecondsAllTime = 1000L, revision = 1L)))

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
                handler.onEvent(created(payload("user-3", revision = 1L)))
                handler.onCatchUpItem(payload("user-3", revision = 3L), isTombstone = false)

                val rows = db.publicProfileDao().digestRows(Long.MAX_VALUE)
                val row = rows.first { it.id == "user-3" }
                row.revision shouldBe 3L
            }
        }

        test("Deleted event soft-deletes the row so observeAll no longer returns it") {
            withHandler { handler, db ->
                // Seed a live row via a Created event
                handler.onEvent(created(payload("user-deleted", revision = 1L)))

                // Apply a live Deleted event — this was previously a no-op (the bug)
                handler
                    .onEvent(
                        SyncEvent.Deleted(id = "user-deleted", revision = 2L, occurredAt = 999_000L),
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                // The tombstoned row is retained (revision bumped) but EXCLUDED from the digest;
                // read its revision via the tombstone-inclusive revisionOf, not digestRows.
                db.publicProfileDao().revisionOf("user-deleted") shouldBe 2L
                db.publicProfileDao().digestRows(Long.MAX_VALUE).map { it.id } shouldNotContain "user-deleted"
                // observeAll() only emits live rows; confirm the row is absent there
                // (collect one emission — we're inside runTest so the Room Flow is synchronous)
                val liveRows = db.publicProfileDao().observeAll().first()
                liveRows.none { it.id == "user-deleted" } shouldBe true
            }
        }

        test("tombstoned row is EXCLUDED from digestRows — the digest counts live rows only (F1)") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("user-tomb-digest", revision = 1L)))
                handler.onEvent(
                    SyncEvent.Deleted(id = "user-tomb-digest", revision = 2L, occurredAt = 999_000L),
                )
                // observeById filters tombstones — invisible to reads
                db.publicProfileDao().observeById("user-tomb-digest").first() shouldBe null
                // and EXCLUDED from the digest — the digest counts live rows only, so a client that
                // tombstoned this row locally converges (F1). Deletions still reach clients via the
                // firehose and the tombstone-ungated access-filtered catch-up. Digest-drift reconciliation
                db.publicProfileDao().digestRows(Long.MAX_VALUE).map { it.id } shouldNotContain "user-tomb-digest"
            }
        }

        test("onCatchUpItem with isTombstone=true soft-deletes the row") {
            withHandler { handler, db ->
                // First, upsert a live row
                handler.onEvent(created(payload("user-4", revision = 1L)))

                // Then receive a tombstone
                handler
                    .onCatchUpItem(
                        payload("user-4", revision = 2L, deletedAt = 999_000L),
                        isTombstone = true,
                    ).shouldBeInstanceOf<AppResult.Success<Unit>>()

                // The row is retained (revision bumped) but EXCLUDED from the digest; read its
                // revision via the tombstone-inclusive revisionOf, not digestRows.
                db.publicProfileDao().revisionOf("user-4") shouldBe 2L
                db.publicProfileDao().digestRows(Long.MAX_VALUE).map { it.id } shouldNotContain "user-4"
            }
        }

        test("tagline is mapped through sync and emitted by observeById") {
            withHandler { handler, db ->
                val p = payload("user-tagline", tagline = "Audiobook enthusiast")
                handler.onEvent(created(p)).shouldBeInstanceOf<AppResult.Success<Unit>>()

                val entity = db.publicProfileDao().observeById("user-tagline").first()
                entity shouldNotBe null
                entity!!.tagline shouldBe "Audiobook enthusiast"
            }
        }

        test("tagline null payload produces null tagline in entity") {
            withHandler { handler, db ->
                val p = payload("user-no-tagline", tagline = null)
                handler.onEvent(created(p)).shouldBeInstanceOf<AppResult.Success<Unit>>()

                val entity = db.publicProfileDao().observeById("user-no-tagline").first()
                entity shouldNotBe null
                entity!!.tagline shouldBe null
            }
        }

        test("observeById returns null for absent userId") {
            withHandler { _, db ->
                val entity = db.publicProfileDao().observeById("nonexistent").first()
                entity shouldBe null
            }
        }

        test("observeById returns null for tombstoned row") {
            withHandler { handler, db ->
                handler.onEvent(created(payload("user-tomb", revision = 1L)))
                handler.onEvent(
                    SyncEvent.Deleted(id = "user-tomb", revision = 2L, occurredAt = 999_000L),
                )

                val entity = db.publicProfileDao().observeById("user-tomb").first()
                entity shouldBe null
            }
        }

        test("handler self-registers under domainName 'public_profiles'") {
            val registry = ClientSyncDomainRegistry()
            val db = createInMemoryTestDatabase()
            try {
                val handler =
                    publicProfilesDomain(db, FakeAvatarDownloadRepository())
                        .toHandler(RoomTransactionRunner(db), registry)
                handler.domainName shouldBe "public_profiles"
                registry.lookup("public_profiles") shouldBe handler
            } finally {
                db.close()
            }
        }

        test("image avatar with changed avatarUpdatedAt triggers queueAvatarForceRefresh") {
            val fakeRepo = FakeAvatarDownloadRepository()
            withHandler(fakeRepo) { handler, _ ->
                val p = payload("user-avatar-refresh", avatarType = "image", avatarUpdatedAt = 100L)
                handler.onEvent(created(p))
                fakeRepo.forceRefreshCalls shouldBe listOf("user-avatar-refresh")
            }
        }

        test("same avatarUpdatedAt does NOT trigger queueAvatarForceRefresh on second upsert") {
            val fakeRepo = FakeAvatarDownloadRepository()
            withHandler(fakeRepo) { handler, _ ->
                val p = payload("user-no-refresh", avatarType = "image", avatarUpdatedAt = 100L)
                handler.onEvent(created(p))
                // same avatarUpdatedAt — should NOT trigger another refresh
                handler.onEvent(updated(p))
                fakeRepo.forceRefreshCalls.size shouldBe 1
            }
        }

        test("avatarUpdatedAt advancing from an existing value re-triggers the refresh") {
            val fakeRepo = FakeAvatarDownloadRepository()
            withHandler(fakeRepo) { handler, _ ->
                handler.onEvent(created(payload("user-advance", avatarType = "image", avatarUpdatedAt = 100L)))
                handler.onEvent(
                    updated(payload("user-advance", avatarType = "image", avatarUpdatedAt = 200L, revision = 2L)),
                )
                fakeRepo.forceRefreshCalls shouldBe listOf("user-advance", "user-advance")
            }
        }

        test("non-image avatar with changed avatarUpdatedAt does NOT trigger a refresh") {
            val fakeRepo = FakeAvatarDownloadRepository()
            withHandler(fakeRepo) { handler, _ ->
                val p = payload("user-auto-avatar", avatarType = "auto", avatarUpdatedAt = 100L)
                handler.onEvent(created(p))
                fakeRepo.forceRefreshCalls shouldBe emptyList<String>()
            }
        }
    })

// ── Fixtures ─────────────────────────────────────────────────────────────────

private class FakeAvatarDownloadRepository : AvatarDownloadRepository {
    val forceRefreshCalls = mutableListOf<String>()

    override fun queueAvatarDownload(userId: String) = Unit

    override fun queueAvatarForceRefresh(userId: String) {
        forceRefreshCalls.add(userId)
    }

    override suspend fun deleteAvatar(userId: String) = Unit
}

private fun withHandler(
    fakeAvatarRepo: FakeAvatarDownloadRepository = FakeAvatarDownloadRepository(),
    block: suspend (SyncDomainHandler<PublicProfileSyncPayload>, ListenUpDatabase) -> Unit,
) = runTest {
    val db = createInMemoryTestDatabase()
    try {
        block(
            publicProfilesDomain(db, fakeAvatarRepo)
                .toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry()),
            db,
        )
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
    tagline: String? = null,
    avatarType: String = "auto",
    avatarUpdatedAt: Long = 0L,
) = PublicProfileSyncPayload(
    id = id,
    displayName = "Test User",
    avatarType = avatarType,
    tagline = tagline,
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
    avatarUpdatedAt = avatarUpdatedAt,
)
