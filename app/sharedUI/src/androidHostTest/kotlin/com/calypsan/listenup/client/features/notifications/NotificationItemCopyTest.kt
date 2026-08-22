package com.calypsan.listenup.client.features.notifications

import com.calypsan.listenup.api.notifications.NotificationEvent
import com.calypsan.listenup.api.notifications.NotificationTypes
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.notifications_campfire_invite_body
import listenup.composeapp.generated.resources.notifications_campfire_invite_title
import listenup.composeapp.generated.resources.notifications_registration_approval_body
import listenup.composeapp.generated.resources.notifications_registration_approval_title
import listenup.composeapp.generated.resources.notifications_registration_decision_approved_body
import listenup.composeapp.generated.resources.notifications_registration_decision_approved_title
import listenup.composeapp.generated.resources.notifications_registration_decision_denied_body
import listenup.composeapp.generated.resources.notifications_registration_decision_denied_title
import listenup.composeapp.generated.resources.notifications_unknown_subtitle
import listenup.composeapp.generated.resources.notifications_unknown_title

/**
 * Registry-completeness pins for the notification copy resolvers. These are the forcing functions
 * the spec demands: adding a notification type without display copy fails here, not in production
 * with a blank row.
 */
class NotificationItemCopyTest :
    FunSpec({
        test("every registered notification type resolves a Settings display-name resource") {
            NotificationTypes.all.keys.forEach { type ->
                notificationTypeNameRes(type).shouldNotBeNull()
            }
        }

        test("an unknown type key resolves no Settings display name") {
            notificationTypeNameRes("some_future_type") shouldBe null
        }

        test("every NotificationEvent case resolves item title and body resources") {
            notificationItemCopyRes(NotificationEvent.CampfireInvite("cf-1", "b-1", "u-1")) shouldBe
                (Res.string.notifications_campfire_invite_title to Res.string.notifications_campfire_invite_body)
            notificationItemCopyRes(NotificationEvent.RegistrationApproval("u-9")) shouldBe
                (
                    Res.string.notifications_registration_approval_title to
                        Res.string.notifications_registration_approval_body
                )
            // The null-event generic fallback renders the unknown_* copy — old builds must never
            // drop a notification they cannot name.
            notificationItemCopyRes(event = null) shouldBe
                (Res.string.notifications_unknown_title to Res.string.notifications_unknown_subtitle)
        }

        test("RegistrationDecision resolves the approved pair when approved, the denied pair when not") {
            notificationItemCopyRes(NotificationEvent.RegistrationDecision("u-7", approved = true)) shouldBe
                (
                    Res.string.notifications_registration_decision_approved_title to
                        Res.string.notifications_registration_decision_approved_body
                )
            notificationItemCopyRes(NotificationEvent.RegistrationDecision("u-7", approved = false)) shouldBe
                (
                    Res.string.notifications_registration_decision_denied_title to
                        Res.string.notifications_registration_decision_denied_body
                )
        }
    })
