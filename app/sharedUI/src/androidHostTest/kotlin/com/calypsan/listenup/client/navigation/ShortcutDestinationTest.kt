package com.calypsan.listenup.client.navigation

import com.calypsan.listenup.client.data.repository.ShortcutAction
import com.calypsan.listenup.client.features.admin.AdminFocus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Which screen a notification tap, shade tap or launcher shortcut opens.
 *
 * `PushRoutingContractTest` and `NotificationTapRoutingTest` pin everything up to the
 * [ShortcutAction]; this pins the last hop, from the action to the screens it stacks on the shell.
 */
class ShortcutDestinationTest :
    FunSpec({
        test("a book shortcut opens that book") {
            ShortcutAction.NavigateToBook(bookId = "b-stormlight").screensAboveShell() shouldBe
                listOf(BookDetail(bookId = "b-stormlight"))
        }

        test("an import shortcut opens the import flow, over Backups") {
            ShortcutAction.NavigateToAbsImport(importId = "imp-1").screensAboveShell() shouldBe
                listOf(AdminBackups, ImportFlow)
        }

        test("a pending-approval shortcut opens Admin on its pending requests, where the decision is made") {
            ShortcutAction.NavigateToPendingApprovals(userId = "u-ada").screensAboveShell() shouldBe
                listOf(Admin(focus = AdminFocus.PENDING_REGISTRATIONS))
        }

        test("a profile shortcut opens that listener's profile") {
            ShortcutAction.NavigateToUserProfile(userId = "u-ada").screensAboveShell() shouldBe
                listOf(UserProfile(userId = "u-ada"))
        }

        test("the actions that play rather than navigate open no screen") {
            listOf(
                ShortcutAction.Resume,
                ShortcutAction.PlayBook(bookId = "b1"),
                ShortcutAction.Search,
                ShortcutAction.SleepTimer(timerMinutes = 15),
            ).forEach { it.screensAboveShell().shouldBeEmpty() }
        }
    })
