package com.calypsan.listenup.api.sync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class AdminUserRosterSyncPayloadTest :
    FunSpec({
        val json = Json { ignoreUnknownKeys = true }

        test("AdminUserRosterSyncPayload round-trips through a SyncEvent.Created envelope") {
            val payload =
                AdminUserRosterSyncPayload(
                    id = "user-1",
                    email = "alice@example.com",
                    displayName = "Alice Anderson",
                    role = "MEMBER",
                    status = "ACTIVE",
                    canShare = true,
                    accountCreatedAt = 1_000L,
                    revision = 5L,
                    createdAt = 900L,
                    updatedAt = 1_000L,
                    deletedAt = null,
                )
            val event: SyncEvent<AdminUserRosterSyncPayload> =
                SyncEvent.Created(id = payload.id, revision = 5L, occurredAt = 1_000L, clientOpId = null, payload = payload)

            val serializer = SyncEvent.serializer(AdminUserRosterSyncPayload.serializer())
            val decoded = json.decodeFromString(serializer, json.encodeToString(serializer, event))

            decoded shouldBe event
        }
    })
