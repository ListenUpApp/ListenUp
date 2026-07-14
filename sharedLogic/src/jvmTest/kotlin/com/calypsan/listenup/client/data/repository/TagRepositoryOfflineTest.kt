package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.TagService
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.BookTagEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TagEntity
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
import com.calypsan.listenup.api.sync.Tag as WireTag

/**
 * Offline-first contract for the tag write surface. Every server-mutating op that CAN be mirrored
 * optimistically (rename, delete, remove-from-book) must write Room and enqueue a durable outbox op
 * with no server present — never fail with a [com.calypsan.listenup.api.error.ServerConnectError].
 * Adding a tag to a book is offline-first when a same-name tag already exists locally (the server's
 * find-or-create resolves to that same tag), and falls back online otherwise (a genuinely new tag
 * mints a server-side id that can't be mirrored optimistically).
 */
class TagRepositoryOfflineTest :
    FunSpec({
        // A no-op sender so drain never fires during the unit test; the assertion is on what was enqueued.
        fun fixture(): TestFixture {
            val db = createInMemoryTestDatabase()
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
            return TestFixture(db, offlineEditor)
        }

        test("renameTag persists the new name to Room and enqueues a tags op keyed by tagId") {
            runTest {
                val f = fixture()
                f.db.tagDao().upsert(TagEntity(id = "t1", name = "Sci Fi", slug = "sci-fi", revision = 3, updatedAt = 0L))
                val repo = f.repo(mock())

                val result = repo.renameTag("t1", "Science Fiction")

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                f.db
                    .tagDao()
                    .getById("t1")
                    ?.name shouldBe "Science Fiction"
                // Slug and revision are left untouched — the echo is the final word.
                f.db
                    .tagDao()
                    .getById("t1")
                    ?.slug shouldBe "sci-fi"
                val op =
                    f.db
                        .pendingOperationV2Dao()
                        .nextDispatchable()
                        .single()
                op.domainName shouldBe "tags"
                op.entityId shouldBe "t1"
                op.opType shouldBe "update"
                f.db.close()
            }
        }

        test("deleteTag soft-deletes the tag, cascade-tombstones its junctions, and enqueues a tags delete op") {
            runTest {
                val f = fixture()
                f.db.tagDao().upsert(TagEntity(id = "t1", name = "Sci Fi", slug = "sci-fi", revision = 2, updatedAt = 0L))
                f.db.bookTagDao().upsert(BookTagEntity(bookId = "b1", tagId = "t1", createdAt = 0L, revision = 1))
                f.db.bookTagDao().upsert(BookTagEntity(bookId = "b2", tagId = "t1", createdAt = 0L, revision = 1))
                val repo = f.repo(mock())

                val result = repo.deleteTag("t1")

                result shouldBe AppResult.Success(Unit)
                // Tag tombstoned (getById excludes tombstones) and both junctions gone from live views.
                f.db
                    .tagDao()
                    .getById("t1")
                    .shouldBeNull()
                f.db
                    .bookTagDao()
                    .findByKey("b1", "t1")
                    ?.deletedAt
                    .shouldNotBeNull()
                f.db
                    .bookTagDao()
                    .findByKey("b2", "t1")
                    ?.deletedAt
                    .shouldNotBeNull()
                val op =
                    f.db
                        .pendingOperationV2Dao()
                        .nextDispatchable()
                        .single()
                op.domainName shouldBe "tags"
                op.entityId shouldBe "t1"
                op.opType shouldBe "delete"
                f.db.close()
            }
        }

        test("removeTagFromBook tombstones the junction and enqueues a book_tags op keyed by \$bookId:\$tagId") {
            runTest {
                val f = fixture()
                f.db.bookTagDao().upsert(BookTagEntity(bookId = "b1", tagId = "t1", createdAt = 0L, revision = 1))
                val repo = f.repo(mock())

                val result = repo.removeTagFromBook(bookId = "b1", tagId = "t1")

                result shouldBe AppResult.Success(Unit)
                f.db
                    .bookTagDao()
                    .findByKey("b1", "t1")
                    ?.deletedAt
                    .shouldNotBeNull()
                val op =
                    f.db
                        .pendingOperationV2Dao()
                        .nextDispatchable()
                        .single()
                op.domainName shouldBe "book_tags"
                op.entityId shouldBe "b1:t1"
                op.opType shouldBe "delete"
                f.db.close()
            }
        }

        test("addTagToBook with an existing same-name tag is offline-first: upserts the junction and enqueues a create op") {
            runTest {
                val f = fixture()
                // The display name is "Sci-Fi"; the caller types "sci-fi" — a case-insensitive hit.
                f.db.tagDao().upsert(TagEntity(id = "t1", name = "Sci-Fi", slug = "sci-fi", revision = 4, updatedAt = 0L))
                // The service must NOT be called on the offline-first hit path — a bare mock proves it.
                val repo = f.repo(mock())

                val result = repo.addTagToBook(bookId = "b1", name = "sci-fi")

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                (result as AppResult.Success).data.id shouldBe "t1"
                // The junction is upserted optimistically and live.
                f.db
                    .bookTagDao()
                    .findByKey("b1", "t1")
                    .shouldNotBeNull()
                    .deletedAt
                    .shouldBeNull()
                val op =
                    f.db
                        .pendingOperationV2Dao()
                        .nextDispatchable()
                        .single()
                op.domainName shouldBe "book_tags"
                op.entityId shouldBe "b1:t1"
                op.opType shouldBe "create"
                f.db.close()
            }
        }

        test("addTagToBook with no same-name tag stays online — it dispatches to the RPC and enqueues nothing") {
            runTest {
                val f = fixture()
                val service = mock<TagService>()
                everySuspend { service.addTagToBook(any(), any()) } returns
                    AppResult.Success(WireTag(id = "t1", name = "Sci Fi", slug = "sci-fi", revision = 1, updatedAt = 0L))
                val repo = f.repo(service)

                val result = repo.addTagToBook(bookId = "b1", name = "Brand New")

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                f.db
                    .pendingOperationV2Dao()
                    .nextDispatchable()
                    .shouldBeEmpty()
                f.db.close()
            }
        }
    })

private class TestFixture(
    val db: ListenUpDatabase,
    private val offlineEditor: OfflineEditor,
) {
    fun repo(service: TagService): TagRepositoryImpl =
        TagRepositoryImpl(
            channel = RpcChannel.forTest(service),
            tagDao = db.tagDao(),
            bookTagDao = db.bookTagDao(),
            offlineEditor = offlineEditor,
        )
}
