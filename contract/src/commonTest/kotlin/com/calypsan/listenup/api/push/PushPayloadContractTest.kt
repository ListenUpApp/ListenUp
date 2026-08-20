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

        test("RegistrationDecision round-trips") {
            val original: PushPayload = PushPayload.RegistrationDecision(userId = "user-9", approved = true)
            val json = contractJson.encodeToString(PushPayload.serializer(), original)
            contractJson.decodeFromString(PushPayload.serializer(), json) shouldBe original
        }

        test("RegistrationApproval round-trips") {
            val original: PushPayload = PushPayload.RegistrationApproval(userId = "user-7")
            val json = contractJson.encodeToString(PushPayload.serializer(), original)
            contractJson.decodeFromString(PushPayload.serializer(), json) shouldBe original
        }

        // The IDs-only rule is a privacy boundary, not a style preference: the relay is
        // third-party infrastructure, so a display name in the payload would leak the identity of
        // everyone requesting access to a private server. The admin's client already has the name
        // in its synced roster.
        test("RegistrationApproval carries no display data") {
            val json =
                contractJson.encodeToString(
                    PushPayload.serializer(),
                    PushPayload.RegistrationApproval(userId = "user-7"),
                )
            json shouldBe """{"type":"registration_approval","userId":"user-7"}"""
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
            contractJson.encodeToString(
                PushPayload.serializer(),
                PushPayload.RegistrationDecision("u", approved = false),
            ) shouldContain "\"registration_decision\""
            contractJson.encodeToString(
                PushPayload.serializer(),
                PushPayload.RegistrationApproval("u"),
            ) shouldContain "\"registration_approval\""
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
