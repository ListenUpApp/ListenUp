package com.calypsan.listenup.api.notifications

import com.calypsan.listenup.api.push.PushPayload
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class NotificationPushProjectionTest :
    FunSpec({
        test("every push-eligible case projects to a PushPayload") {
            val cases: List<NotificationEvent> =
                listOf(
                    NotificationEvent.CampfireInvite("cf-1", "b-1", "u-1"),
                    NotificationEvent.RegistrationDecision("u-7", approved = false),
                    NotificationEvent.RegistrationApproval("u-9"),
                )
            cases.filter { it.descriptor.pushEligible }.forEach { event ->
                event.toPushPayload().shouldNotBeNull()
            }
        }

        test("projections carry the ids across unchanged") {
            NotificationEvent.CampfireInvite("cf-1", "b-1", "u-1").toPushPayload() shouldBe
                PushPayload.CampfireInvite(campfireId = "cf-1", bookId = "b-1", inviterUserId = "u-1")
            NotificationEvent.RegistrationDecision("u-7", approved = true).toPushPayload() shouldBe
                PushPayload.RegistrationDecision(userId = "u-7", approved = true)
            NotificationEvent.RegistrationApproval("u-9").toPushPayload() shouldBe
                PushPayload.RegistrationApproval(userId = "u-9")
        }

        test("toNotificationEvent inverts toPushPayload for every event case") {
            val cases: List<NotificationEvent> =
                listOf(
                    NotificationEvent.CampfireInvite("cf-1", "b-1", "u-1"),
                    NotificationEvent.RegistrationDecision("u-7", approved = true),
                    NotificationEvent.RegistrationApproval("u-9"),
                )
            cases.forEach { event ->
                event.toPushPayload()?.toNotificationEvent() shouldBe event
            }
        }

        test("a TestNotification push has no notification event — it is a diagnostic") {
            PushPayload.TestNotification(sentAtMs = 1L).toNotificationEvent() shouldBe null
        }
    })
