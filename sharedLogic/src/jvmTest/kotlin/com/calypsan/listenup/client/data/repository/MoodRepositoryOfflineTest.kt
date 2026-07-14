package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.MoodService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.BookMoodEntity
import com.calypsan.listenup.client.data.local.db.MoodEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import com.calypsan.listenup.api.sync.Mood as WireMood

/**
 * Offline-first contract for the mood write surface. Removing a mood from a book must write Room and
 * enqueue a durable outbox op with no server present. Adding a mood is offline-first when a same-name
 * mood already exists locally (the server's find-or-create resolves to it), and falls back online
 * otherwise (a genuinely new mood mints a server-side id). Mood has no client rename/delete.
 */
class MoodRepositoryOfflineTest :
    FunSpec({
        test("removeMoodFromBook tombstones the junction and enqueues a book_moods op keyed by \$bookId:\$moodId") {
            runTest {
                val db = createInMemoryTestDatabase()
                db.bookMoodDao().upsert(BookMoodEntity(bookId = "b1", moodId = "m1", createdAt = 0L, revision = 1))
                val repo = repo(db, mock())

                val result = repo.removeMoodFromBook(bookId = "b1", moodId = "m1")

                result shouldBe AppResult.Success(Unit)
                db
                    .bookMoodDao()
                    .findByKey("b1", "m1")
                    ?.deletedAt
                    .shouldNotBeNull()
                val op = db.pendingOperationV2Dao().nextDispatchable().single()
                op.domainName shouldBe "book_moods"
                op.entityId shouldBe "b1:m1"
                op.opType shouldBe "delete"
                db.close()
            }
        }

        test("addMoodToBook with an existing same-name mood is offline-first: upserts the junction and enqueues a create op") {
            runTest {
                val db = createInMemoryTestDatabase()
                // The display name is "Tense"; the caller types "tense" — a case-insensitive hit.
                db.moodDao().upsert(MoodEntity(id = "m1", name = "Tense", slug = "tense", revision = 2, updatedAt = 0L))
                // The service must NOT be called on the offline-first hit path — a bare mock proves it.
                val repo = repo(db, mock())

                val result = repo.addMoodToBook(bookId = "b1", name = "tense")

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                (result as AppResult.Success).data.id shouldBe "m1"
                db
                    .bookMoodDao()
                    .findByKey("b1", "m1")
                    .shouldNotBeNull()
                    .deletedAt
                    .shouldBeNull()
                val op = db.pendingOperationV2Dao().nextDispatchable().single()
                op.domainName shouldBe "book_moods"
                op.entityId shouldBe "b1:m1"
                op.opType shouldBe "create"
                db.close()
            }
        }

        test("addMoodToBook with no same-name mood stays online — it dispatches to the RPC and enqueues nothing") {
            runTest {
                val db = createInMemoryTestDatabase()
                val service = mock<MoodService>()
                everySuspend { service.addMoodToBook(any(), any()) } returns
                    AppResult.Success(
                        WireMood(id = "m1", name = "Tense", slug = "tense", revision = 1, updatedAt = 0L),
                    )
                val repo = repo(db, service)

                val result = repo.addMoodToBook(bookId = "b1", name = "Brand New")

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                db.pendingOperationV2Dao().nextDispatchable().shouldBeEmpty()
                db.close()
            }
        }
    })

private fun repo(
    db: com.calypsan.listenup.client.data.local.db.ListenUpDatabase,
    service: MoodService,
): MoodRepositoryImpl {
    val queue =
        PendingOperationQueue(
            dao = db.pendingOperationV2Dao(),
            sender = PendingOperationSender { AppResult.Success(Unit) },
        )
    val offlineEditor =
        OfflineEditor(
            pendingQueue = queue,
            transactionRunner =
                object : TransactionRunner {
                    override suspend fun <R> atomically(block: suspend () -> R): R = block()
                },
            authSession = FakeAuthSession(userId = "u1"),
        )
    return MoodRepositoryImpl(
        channel = RpcChannel.forTest(service),
        moodDao = db.moodDao(),
        bookMoodDao = db.bookMoodDao(),
        offlineEditor = offlineEditor,
    )
}
