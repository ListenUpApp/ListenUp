package com.calypsan.listenup.client.presentation.notifications

import com.calypsan.listenup.api.notifications.NotificationEvent
import com.calypsan.listenup.api.notifications.NotificationTarget
import com.calypsan.listenup.client.data.repository.ShortcutAction
import com.calypsan.listenup.client.domain.model.AppNotification
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class NotificationTapRoutingTest :
    FunSpec({
        fun notification(event: NotificationEvent?): AppNotification =
            AppNotification(
                id = "n-1",
                type = event?.wireType ?: "future_type",
                event = event,
                createdAt = 1_000L,
                readAt = null,
            )

        test("RegistrationApproval routes to the pending approvals list with the event's userId") {
            notification(NotificationEvent.RegistrationApproval("u-9")).toShortcutAction() shouldBe
                ShortcutAction.NavigateToPendingApprovals(userId = "u-9")
        }

        test("RegistrationDecision has no destination — target None just opens the app") {
            notification(NotificationEvent.RegistrationDecision("u-7", approved = true))
                .toShortcutAction()
                .shouldBeNull()
        }

        test("CampfireInvite maps to null today — no campfire surface exists yet (#1065)") {
            // The mapping's `when` is exhaustive on NotificationTarget, so a future Campfire
            // route forces a conscious edit there; this pins today's open-the-app behaviour.
            notification(NotificationEvent.CampfireInvite("cf-1", "b-1", "u-1"))
                .toShortcutAction()
                .shouldBeNull()
        }

        test("an unknown-type notification (null event) opens the app generically") {
            notification(event = null).toShortcutAction().shouldBeNull()
        }

        test("a Book target routes to the book's detail screen") {
            // No current event carries a Book target, so the target-level mapping is pinned
            // directly — the event-level function delegates to this same `when`.
            NotificationTarget.Book(bookId = "b-42").toShortcutAction(eventForContext = null) shouldBe
                ShortcutAction.NavigateToBook(bookId = "b-42")
        }

        test("a Profile target routes to the user's profile") {
            NotificationTarget.Profile(userId = "u-3").toShortcutAction(eventForContext = null) shouldBe
                ShortcutAction.NavigateToUserProfile(userId = "u-3")
        }
    })
