package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.NotificationService
import com.calypsan.listenup.api.notifications.NotificationEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.NotificationEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.OfflineEditor
import com.calypsan.listenup.client.data.sync.PendingOperationQueue
import com.calypsan.listenup.client.data.sync.PendingOperationSender
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Offline-first contract for the notification inbox write surface. `markRead` must stamp Room
 * optimistically and enqueue a durable outbox op with NO server present — the fixture has no
 * server at all (the service is a bare, unstubbed mock: any dispatch through it would throw),
 * so a green run proves Room-before-network by construction. Reads decode lazily: an unknown
 * event type maps to [com.calypsan.listenup.client.domain.model.AppNotification] with
 * `event == null` and MUST render generically, never drop.
 */
class NotificationRepositoryOfflineTest :
    FunSpec({
        // A no-op sender so drain never fires during the unit test; the assertion is on what was enqueued.
        fun fixture(): NotificationFixture {
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
            return NotificationFixture(db, offlineEditor)
        }

        suspend fun seed(
            db: ListenUpDatabase,
            id: String,
            type: String = "registration_approval",
            eventJson: String = """{"type":"registration_approval","userId":"u1"}""",
            readAt: Long? = null,
        ) = db.notificationDao().upsert(
            NotificationEntity(
                id = id,
                type = type,
                eventJson = eventJson,
                createdAt = 100L,
                updatedAt = 100L,
                readAt = readAt,
                revision = 1L,
            ),
        )

        test("markRead stamps readAt locally immediately — the read row is observable before any network") {
            runTest {
                val f = fixture()
                seed(f.db, "n1")
                // A bare, unstubbed mock: the fixture has no server, and any dispatch would throw.
                val repo = f.repo(mock())

                val result = repo.markRead("n1")

                result.shouldBeInstanceOf<AppResult.Success<*>>()
                val row = repo.observeNotifications().first().single()
                row.readAt.shouldNotBeNull()
                row.isUnread shouldBe false
                f.db.close()
            }
        }

        test("markRead enqueues exactly one op on the notifications channel keyed by the row id") {
            runTest {
                val f = fixture()
                seed(f.db, "n1")
                val repo = f.repo(mock())

                repo.markRead("n1").shouldBeInstanceOf<AppResult.Success<*>>()

                val op =
                    f.db
                        .pendingOperationV2Dao()
                        .nextDispatchable()
                        .single()
                op.domainName shouldBe "notifications"
                op.entityId shouldBe "n1"
                op.opType shouldBe "update"
                f.db.close()
            }
        }

        test("markRead twice enqueues two ops (no coalescing) — Room's readAt guard keeps the first stamp") {
            runTest {
                val f = fixture()
                seed(f.db, "n1")
                val repo = f.repo(mock())

                repo.markRead("n1").shouldBeInstanceOf<AppResult.Success<*>>()
                val firstReadAt =
                    repo
                        .observeNotifications()
                        .first()
                        .single()
                        .readAt
                repo.markRead("n1").shouldBeInstanceOf<AppResult.Success<*>>()

                // Observed behaviour, pinned: OfflineEditor enqueues without coalescing, so the second
                // markRead queues a SECOND durable op (countDispatchable == 2); per-entity FIFO means
                // nextDispatchable exposes only the head op for the row until it drains. Harmless,
                // because MarkRead is idempotent server-side (last-write-wins on the row) and the
                // DAO's `readAt IS NULL` guard makes the local stamp first-write-wins.
                f.db.pendingOperationV2Dao().countDispatchable() shouldBe 2
                f.db
                    .pendingOperationV2Dao()
                    .nextDispatchable()
                    .single()
                    .entityId shouldBe "n1"
                repo
                    .observeNotifications()
                    .first()
                    .single()
                    .readAt shouldBe firstReadAt
                f.db.close()
            }
        }

        test("observeUnreadCount drops by one after markRead") {
            runTest {
                val f = fixture()
                seed(f.db, "n1")
                seed(f.db, "n2")
                val repo = f.repo(mock())

                repo.observeUnreadCount().first() shouldBe 2

                repo.markRead("n1").shouldBeInstanceOf<AppResult.Success<*>>()

                repo.observeUnreadCount().first() shouldBe 1
                f.db.close()
            }
        }

        test("an unknown-type row maps to AppNotification with event == null (generic rendering contract)") {
            runTest {
                val f = fixture()
                seed(f.db, "n9", type = "books_added", eventJson = """{"type":"books_added","count":37}""")
                seed(f.db, "n1")
                val repo = f.repo(mock())

                val notifications = repo.observeNotifications().first()

                val unknown = notifications.single { it.id == "n9" }
                unknown.type shouldBe "books_added"
                unknown.event.shouldBeNull()
                unknown.isUnread shouldBe true
                // A known type decodes to its typed event alongside it.
                notifications
                    .single { it.id == "n1" }
                    .event
                    .shouldBeInstanceOf<NotificationEvent.RegistrationApproval>()
                f.db.close()
            }
        }
    })

private class NotificationFixture(
    val db: ListenUpDatabase,
    private val offlineEditor: OfflineEditor,
) {
    fun repo(service: NotificationService): NotificationRepositoryImpl =
        NotificationRepositoryImpl(
            channel = RpcChannel.forTest(service),
            notificationDao = db.notificationDao(),
            offlineEditor = offlineEditor,
        )
}
