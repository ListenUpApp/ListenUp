package com.calypsan.listenup.api.sync

import com.calypsan.listenup.api.contractJson
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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

        // Compat shim: admin apps from before the "Can share" permission was removed decode this
        // field as REQUIRED. If a payload ever ships without it, their roster sync freezes on a
        // page that cannot decode. contractJson does not encode defaults, so this pins that the
        // field is emitted anyway.
        test("the encoded roster payload still carries canShare true for un-updated admin clients") {
            val payload =
                AdminUserRosterSyncPayload(
                    id = "user-1",
                    email = "alice@example.com",
                    displayName = "Alice Anderson",
                    role = "MEMBER",
                    status = "ACTIVE",
                    accountCreatedAt = 1_000L,
                    revision = 5L,
                    createdAt = 900L,
                    updatedAt = 1_000L,
                    deletedAt = null,
                )

            contractJson.encodeToString(AdminUserRosterSyncPayload.serializer(), payload) shouldContain
                "\"canShare\":true"
        }
    })
