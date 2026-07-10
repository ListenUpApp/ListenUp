package com.calypsan.listenup.api.push

import com.calypsan.listenup.api.contractJson
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.SerializationException

class PushPayloadContractTest :
    FunSpec({
        test("TestNotification round-trips") {
            val original: PushPayload = PushPayload.TestNotification(sentAtMs = 1_752_105_600_000)
            val json = contractJson.encodeToString(PushPayload.serializer(), original)
            contractJson.decodeFromString(PushPayload.serializer(), json) shouldBe original
        }

        test("CampfireInvite round-trips") {
            val original: PushPayload =
                PushPayload.CampfireInvite(
                    campfireId = "cf-1",
                    bookId = "book-1",
                    inviterUserId = "user-1",
                )
            val json = contractJson.encodeToString(PushPayload.serializer(), original)
            contractJson.decodeFromString(PushPayload.serializer(), json) shouldBe original
        }

        test("discriminators are wire-stable") {
            contractJson.encodeToString(
                PushPayload.serializer(),
                PushPayload.TestNotification(0),
            ) shouldContain "\"test\""
            contractJson.encodeToString(
                PushPayload.serializer(),
                PushPayload.CampfireInvite("c", "b", "u"),
            ) shouldContain "\"campfire_invite\""
        }

        test("unknown discriminator fails decode (pins the client generic-branch contract)") {
            shouldThrow<SerializationException> {
                contractJson.decodeFromString(PushPayload.serializer(), """{"type":"from_the_future"}""")
            }
        }

        test("PushPlatform serial names are wire-stable") {
            contractJson.encodeToString(PushPlatform.serializer(), PushPlatform.ANDROID) shouldBe "\"android\""
            contractJson.encodeToString(PushPlatform.serializer(), PushPlatform.IOS) shouldBe "\"ios\""
        }
    })
