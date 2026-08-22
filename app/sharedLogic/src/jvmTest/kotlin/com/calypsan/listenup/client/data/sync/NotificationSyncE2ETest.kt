package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.notifications.NotificationEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.NotificationEntity
import com.calypsan.listenup.client.data.sync.testing.NOTIFICATION_E2E_USER
import com.calypsan.listenup.client.data.sync.testing.NotificationSyncClient
import com.calypsan.listenup.client.data.sync.testing.awaitUntil
import com.calypsan.listenup.client.data.sync.testing.withNotificationSyncEngineAgainstServer
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first

private val ROUND_TRIP_TIMEOUT = 30.seconds

/**
 * Tier 3 e2e tests for the userScoped `notifications` inbox domain — the spec's "read state
 * survives a restart and reaches a second device" claim, proven at the data layer.
 *
 * Server-side emits go through the real [com.calypsan.listenup.server.notifications.NotificationEmitter]
 * (audience → preferences → row mint → prune); the rows cross the live RPC firehose or the
 * cursored catch-up pull into the client's Room `notifications` table via the real
 * [com.calypsan.listenup.client.data.sync.domains.notificationsDomain] handler. `markRead` runs
 * the production offline-first path: optimistic Room stamp + a durable outbox op the engine
 * drains into the server repository's ownership-checked
 * [com.calypsan.listenup.server.sync.NotificationRepository.markRead].
 */
class NotificationSyncE2ETest :
    FunSpec({

        test("server emit → firehose → client Room has the row, unread, event intact") {
            withNotificationSyncEngineAgainstServer {
                clientA.engine.start(currentUserId = NOTIFICATION_E2E_USER)

                val event = NotificationEvent.RegistrationApproval(userId = "u-pending")
                emitTo(NOTIFICATION_E2E_USER, event)

                val row = clientA.awaitInboxRow()
                row.readAt.shouldBeNull()
                row.deletedAt.shouldBeNull()
                row.type shouldBe "registration_approval"
                contractJson.decodeFromString(NotificationEvent.serializer(), row.eventJson) shouldBe event
                clientA.notificationRepo.observeUnreadCount().first() shouldBe 1
            }
        }

        test("client markRead → outbox drain → server row's readAt is stamped") {
            withNotificationSyncEngineAgainstServer {
                clientA.engine.start(currentUserId = NOTIFICATION_E2E_USER)

                emitTo(NOTIFICATION_E2E_USER, NotificationEvent.RegistrationApproval(userId = "u-pending"))
                val row = clientA.awaitInboxRow()

                clientA.notificationRepo
                    .markRead(row.id)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()

                // Optimistic local stamp is immediate; the durable op then drains to the server.
                clientA.notificationRepo.observeUnreadCount().first() shouldBe 0
                awaitUntil(ROUND_TRIP_TIMEOUT) { serverReadAt(row.id) != null }
            }
        }

        test("read state reaches a second device via full catch-up pull") {
            withNotificationSyncEngineAgainstServer {
                clientA.engine.start(currentUserId = NOTIFICATION_E2E_USER)

                emitTo(NOTIFICATION_E2E_USER, NotificationEvent.RegistrationApproval(userId = "u-pending"))
                val row = clientA.awaitInboxRow()
                clientA.notificationRepo
                    .markRead(row.id)
                    .shouldBeInstanceOf<AppResult.Success<Unit>>()
                awaitUntil(ROUND_TRIP_TIMEOUT) { serverReadAt(row.id) != null }

                // A fresh device (empty Room, cursor 0) converges purely through the pull.
                val clientB = newClient()
                clientB.engine.start(currentUserId = NOTIFICATION_E2E_USER)

                awaitUntil(ROUND_TRIP_TIMEOUT) {
                    clientB.clientDatabase
                        .notificationDao()
                        .observeAll()
                        .first()
                        .singleOrNull { it.id == row.id }
                        ?.readAt != null
                }
                clientB.notificationRepo.observeUnreadCount().first() shouldBe 0
            }
        }

        test("a row emitted to a DIFFERENT user never reaches either device") {
            withNotificationSyncEngineAgainstServer {
                clientA.engine.start(currentUserId = NOTIFICATION_E2E_USER)

                // Stray first: if scoping leaked, ordered delivery would land it before the sentinel.
                emitTo("u2", NotificationEvent.RegistrationApproval(userId = "u-other-pending"))
                val strayId = serverLiveIdsFor("u2").single()

                val sentinel = NotificationEvent.RegistrationApproval(userId = "u-sentinel")
                emitTo(NOTIFICATION_E2E_USER, sentinel)

                // Device A (live firehose): sentinel arrives, stray never does.
                val rowA = clientA.awaitInboxRow()
                contractJson.decodeFromString(NotificationEvent.serializer(), rowA.eventJson) shouldBe sentinel
                clientA.clientDatabase
                    .notificationDao()
                    .revisionOf(strayId)
                    .shouldBeNull()

                // Device B (full catch-up pull): same isolation on the pull path.
                val clientB = newClient()
                clientB.engine.start(currentUserId = NOTIFICATION_E2E_USER)
                val rowsB =
                    awaitNonEmpty {
                        clientB.clientDatabase
                            .notificationDao()
                            .observeAll()
                            .first()
                    }
                rowsB.map { it.id } shouldNotContain strayId
                rowsB.single().id shouldBe rowA.id
                clientB.clientDatabase
                    .notificationDao()
                    .revisionOf(strayId)
                    .shouldBeNull()
            }
        }
    })

// ── Helpers ──────────────────────────────────────────────────────────────────

/** Polls this device's Room inbox until exactly one live row is present, and returns it. */
private suspend fun NotificationSyncClient.awaitInboxRow(): NotificationEntity {
    val rows =
        awaitNonEmpty {
            clientDatabase
                .notificationDao()
                .observeAll()
                .first()
        }
    return rows.single()
}

/** Polls [read] until it yields a non-empty list, or fails after the round-trip timeout. */
private suspend fun <T> awaitNonEmpty(read: suspend () -> List<T>): List<T> {
    awaitUntil(ROUND_TRIP_TIMEOUT) { read().isNotEmpty() }
    return read()
}
