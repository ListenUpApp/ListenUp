package com.calypsan.listenup.client.features.notifications

import com.calypsan.listenup.api.notifications.NotificationEvent
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.notifications_campfire_invite_body
import listenup.composeapp.generated.resources.notifications_campfire_invite_title
import listenup.composeapp.generated.resources.notifications_registration_approval_body
import listenup.composeapp.generated.resources.notifications_registration_approval_title
import listenup.composeapp.generated.resources.notifications_registration_decision_approved_body
import listenup.composeapp.generated.resources.notifications_registration_decision_approved_title
import listenup.composeapp.generated.resources.notifications_registration_decision_denied_body
import listenup.composeapp.generated.resources.notifications_registration_decision_denied_title
import listenup.composeapp.generated.resources.notifications_type_campfire_invite
import listenup.composeapp.generated.resources.notifications_type_registration_approval
import listenup.composeapp.generated.resources.notifications_type_registration_decision
import listenup.composeapp.generated.resources.notifications_unknown_subtitle
import listenup.composeapp.generated.resources.notifications_unknown_title
import org.jetbrains.compose.resources.StringResource

/**
 * Resolves the inbox item's title and body string resources for [event]; a null event (a type this
 * build does not know) resolves the generic unknown copy — old builds render, never drop. Pure and
 * exhaustive, so a new [NotificationEvent] case cannot compile without its copy being chosen here
 * (and [NotificationItemCopyTest] pins the resolution).
 */
fun notificationItemCopyRes(event: NotificationEvent?): Pair<StringResource, StringResource> =
    when (event) {
        null -> {
            Res.string.notifications_unknown_title to Res.string.notifications_unknown_subtitle
        }

        is NotificationEvent.CampfireInvite -> {
            Res.string.notifications_campfire_invite_title to Res.string.notifications_campfire_invite_body
        }

        is NotificationEvent.RegistrationDecision -> {
            if (event.approved) {
                Res.string.notifications_registration_decision_approved_title to
                    Res.string.notifications_registration_decision_approved_body
            } else {
                Res.string.notifications_registration_decision_denied_title to
                    Res.string.notifications_registration_decision_denied_body
            }
        }

        is NotificationEvent.RegistrationApproval -> {
            Res.string.notifications_registration_approval_title to
                Res.string.notifications_registration_approval_body
        }
    }

/**
 * Resolves the Settings row display name for a registry [type] key, or null for a key this build
 * does not know — unknown types get no Settings row (nothing to toggle blind; a newer server's
 * types wait for the client update). [NotificationItemCopyTest] pins that every KNOWN registry key
 * resolves.
 */
fun notificationTypeNameRes(type: String): StringResource? =
    when (type) {
        "campfire_invite" -> Res.string.notifications_type_campfire_invite
        "registration_decision" -> Res.string.notifications_type_registration_decision
        "registration_approval" -> Res.string.notifications_type_registration_approval
        else -> null
    }
