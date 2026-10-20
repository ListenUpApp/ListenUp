package com.calypsan.listenup.web.shell

import com.calypsan.listenup.client.presentation.sync.SyncIndicatorUiState
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import com.calypsan.listenup.web.design.MenuAction
import com.calypsan.listenup.web.design.PageHeader
import com.calypsan.listenup.web.design.WebIcon
import com.calypsan.listenup.web.design.buttonClasses
import com.calypsan.listenup.web.features.sync.SyncIndicatorButton
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe

private val PRIMARY =
    NavSection(
        entries =
            listOf(
                NavEntry("home", "Home", WebIcon.Home, href = "/"),
                NavEntry("library", "Library", WebIcon.Book),
                NavEntry("discover", "Discover", WebIcon.Compass),
                NavEntry("search", "Search", WebIcon.Search, onPhone = false),
            ),
    )

private val FOOTER =
    listOf(
        NavEntry("notifications", "Notifications", WebIcon.Bell, badge = 3),
        NavEntry("admin", "Admin", WebIcon.Shield),
        NavEntry("settings", "Settings", WebIcon.Cog),
    )

private const val PHONE = 390
private const val DESKTOP = 1280
private const val MIN_TARGET_PX = 44.0
private const val NOW = 10_000_000L

/**
 * The phone's header (spec §3.1.7 L1/L33): the page's name, then Search, sync, notifications and the account, on one
 * row of 44px targets — what Android and iOS put above their pages, and what the web phone lacked.
 */
class PhoneTopBarTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        class Taps {
            var search = 0
            var notifications = 0
            var refresh = 0
            var admin = 0
        }

        fun shell(
            width: Int,
            taps: Taps = Taps(),
        ) = frames.mount(width) {
            Shell(
                sections = listOf(PRIMARY),
                footer = FOOTER,
                active = "library",
                titled = true,
                topBar = {
                    PhoneTopBar(
                        title = "Library",
                        unread = 3,
                        onSearch = { taps.search++ },
                        onNotifications = { taps.notifications++ },
                        sync = {
                            SyncIndicatorButton(
                                state = SyncIndicatorUiState(lastSyncedAtMs = NOW - 2 * 60_000),
                                now = NOW,
                                onRefresh = { taps.refresh++ },
                            )
                        },
                        account = {
                            AccountMenu(
                                onSignOut = {},
                                destinations =
                                    listOf(
                                        MenuAction("Admin", WebIcon.Shield, { taps.admin++ }),
                                        MenuAction("Settings", WebIcon.Cog, {}),
                                    ),
                                triggerClasses = arrayOf(*buttonClasses(ButtonKind.Icon, ButtonSize.Md), "ptb-b"),
                            )
                        },
                    )
                },
            ) {
                PageHeader(title = "Library")
            }
        }

        test("under 760px the top bar shows the page title and four 44px buttons on one row at 390px") {
            val frame = shell(PHONE)
            awaitFrame()

            frame.find(".ptb-t").textContent shouldBe "Library"
            val buttons = frame.findAll(".ptb .ptb-b").filter(frame::isShown)
            buttons.map { it.getAttribute("aria-label") } shouldBe
                listOf("Search", "Synced 2 min ago. Refresh", "Notifications, 3 unread", "Account")
            buttons.forEach { button ->
                frame.rect(button).width shouldBeGreaterThanOrEqual MIN_TARGET_PX
                frame.rect(button).height shouldBeGreaterThanOrEqual MIN_TARGET_PX
            }
            buttons.map { frame.rect(it).top }.distinct().size shouldBe 1
            val title = frame.rect(frame.find(".ptb-t"))
            title.top shouldBeLessThanOrEqual frame.rect(buttons.first()).bottom
            frame.horizontalOverflow() shouldBe 0
        }

        test("the phone tab bar is Home · Library · Discover") {
            val frame = shell(PHONE)

            frame.findAll(".sidebar .nav-i").filter(frame::isShown).map { it.textContent.orEmpty().trim() } shouldBe
                listOf("Home", "Library", "Discover")
        }

        test("Search and Notifications route in-app, sync refreshes, and the account holds Admin and Settings") {
            val taps = Taps()
            val frame = shell(PHONE, taps)
            awaitFrame()

            frame.findAll(".ptb .ptb-b").first { it.getAttribute("aria-label") == "Search" }.click()
            frame.findAll(".ptb .ptb-b").first { it.getAttribute("aria-label")!!.startsWith("Notifications") }.click()
            frame.findAll(".ptb .ptb-b").first { it.getAttribute("aria-label")!!.startsWith("Synced") }.click()
            taps.search shouldBe 1
            taps.notifications shouldBe 1
            taps.refresh shouldBe 1

            frame.findAll(".ptb .ptb-b").first { it.getAttribute("aria-label") == "Account" }.click()
            awaitFrame()
            val items = frame.findAll(".menu .menu-i").map { it.textContent.orEmpty().trim() }
            items shouldContainAll listOf("Admin", "Settings", "Sign out")
            frame.findAll(".menu .menu-i").first { it.textContent.orEmpty().trim() == "Admin" }.click()
            taps.admin shouldBe 1
        }

        test("one heading per page: the header's name is decoration over the page's own h1, kept for screen readers") {
            val frame = shell(PHONE)
            awaitFrame()

            frame.findAll("h1").size shouldBe 1
            frame.find(".ptb-t").getAttribute("aria-hidden") shouldBe "true"
            frame.rect(frame.find(".page-t")).width shouldBeLessThanOrEqual 1.0
        }

        test("at desktop width there is no phone header, and the page keeps its visible title") {
            val frame = shell(DESKTOP)
            awaitFrame()

            frame.isShown(frame.find(".ptb")) shouldBe false
            frame.rect(frame.find(".page-t")).width shouldBeGreaterThanOrEqual 40.0
        }
    })
