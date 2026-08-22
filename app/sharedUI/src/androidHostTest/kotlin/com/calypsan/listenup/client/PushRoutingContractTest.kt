package com.calypsan.listenup.client

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.notifications.toNotificationEvent
import com.calypsan.listenup.api.push.PushPayload
import com.calypsan.listenup.client.data.repository.ShortcutAction
import com.calypsan.listenup.client.presentation.notifications.toShortcutAction
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * Pins the shade-tap protocol so the shade and the app cannot disagree about where a tap lands.
 *
 * The renderer writes the whole encoded [PushPayload] into the tap intent, and `MainActivity`
 * decodes it and routes through the contract's reverse projection ([toNotificationEvent]) into the
 * one shared target mapping ([toShortcutAction]) — the same mapping the in-app notification list
 * uses. This spec replays that exact pipeline over the wire encoding, so a payload rename, a new
 * discriminator, or a mapping change that would strand a shade tap fails here instead of shipping
 * as a tap that silently opens the app "wherever it was last".
 */
class PushRoutingContractTest :
    FunSpec({
        // MainActivity's PUSH_TAP pipeline, verbatim: decode defensively, project, map.
        fun route(raw: String?): ShortcutAction? =
            raw
                ?.let { runCatching { contractJson.decodeFromString(PushPayload.serializer(), it) }.getOrNull() }
                ?.toNotificationEvent()
                ?.toShortcutAction()

        fun encode(payload: PushPayload): String = contractJson.encodeToString(PushPayload.serializer(), payload)

        test("every notification-bearing payload routes over the wire exactly as its event maps directly") {
            val payloads =
                listOf(
                    PushPayload.RegistrationApproval(userId = "u1"),
                    PushPayload.RegistrationDecision(userId = "u2", approved = true),
                    PushPayload.CampfireInvite(campfireId = "c1", bookId = "b1", inviterUserId = "u3"),
                )
            payloads.forEach { payload ->
                route(encode(payload)) shouldBe payload.toNotificationEvent()?.toShortcutAction()
            }
        }

        test("a tapped registration approval lands the admin on the pending-approvals list") {
            route(encode(PushPayload.RegistrationApproval(userId = "u1"))) shouldBe
                ShortcutAction.NavigateToPendingApprovals(userId = "u1")
        }

        test("a test notification routes to no action - the tap just opens the app") {
            route(encode(PushPayload.TestNotification(sentAtMs = 1L))).shouldBeNull()
        }

        test("an undecodable payload routes to no action without crashing") {
            route("not json at all").shouldBeNull()
        }
    })
