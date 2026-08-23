package com.calypsan.listenup.client.presentation.notifications

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.notifications.NotificationTarget
import com.calypsan.listenup.api.push.PushPayload
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class PushTapRoutingTest :
    FunSpec({
        fun payloadJson(payload: PushPayload): String = contractJson.encodeToString(PushPayload.serializer(), payload)

        test("a registration_approval payload targets the admin inbox") {
            PushTapRouting.targetForPayloadJson(
                payloadJson(PushPayload.RegistrationApproval(userId = "u1")),
            ) shouldBe NotificationTarget.AdminInbox
        }

        test("a registration_decision payload targets None — the tap just opens the app") {
            PushTapRouting.targetForPayloadJson(
                payloadJson(PushPayload.RegistrationDecision(userId = "u1", approved = true)),
            ) shouldBe NotificationTarget.None
        }

        test("a campfire_invite payload carries its campfireId into the Campfire target") {
            PushTapRouting.targetForPayloadJson(
                payloadJson(PushPayload.CampfireInvite(campfireId = "cf-1", bookId = "b-1", inviterUserId = "u-2")),
            ) shouldBe NotificationTarget.Campfire(campfireId = "cf-1")
        }

        test("a test payload is a diagnostic, not a notification — null target") {
            PushTapRouting
                .targetForPayloadJson(payloadJson(PushPayload.TestNotification(sentAtMs = 1_000L)))
                .shouldBeNull()
        }

        test("malformed JSON returns null instead of throwing") {
            PushTapRouting.targetForPayloadJson("not json at all {{{").shouldBeNull()
        }

        test("an unknown future discriminator returns null instead of throwing") {
            PushTapRouting
                .targetForPayloadJson("""{"type":"password_reset_request","userId":"u1"}""")
                .shouldBeNull()
        }
    })
