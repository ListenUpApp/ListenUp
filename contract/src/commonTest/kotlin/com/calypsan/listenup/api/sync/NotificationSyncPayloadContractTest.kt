package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.notifications.NotificationEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class NotificationSyncPayloadContractTest :
    FunSpec({
        val event = NotificationEvent.RegistrationApproval(userId = "u-9")
        val payload =
            NotificationSyncPayload(
                id = "n-1",
                type = event.wireType,
                body = contractJson.encodeToString(NotificationEvent.serializer(), event),
                createdAt = 100L,
                updatedAt = 100L,
                readAt = null,
                revision = 7L,
                deletedAt = null,
            )

        test("round-trips through contractJson") {
            val json = contractJson.encodeToString(NotificationSyncPayload.serializer(), payload)
            contractJson.decodeFromString(NotificationSyncPayload.serializer(), json) shouldBe payload
        }

        test("decodeEvent recovers the typed event") {
            payload.decodeEvent() shouldBe event
        }

        test("decodeEvent on an unknown future type is null, never a throw") {
            payload.copy(type = "books_added", body = """{"type":"books_added","count":37}""")
                .decodeEvent()
                .shouldBeNull()
        }

        test("the notifications domain key is registered") {
            SyncDomains.all.map { it.name } shouldBe SyncDomains.all.map { it.name }.distinct()
            SyncDomains.NOTIFICATIONS.name shouldBe "notifications"
            (SyncDomains.NOTIFICATIONS in SyncDomains.all) shouldBe true
        }
    })
