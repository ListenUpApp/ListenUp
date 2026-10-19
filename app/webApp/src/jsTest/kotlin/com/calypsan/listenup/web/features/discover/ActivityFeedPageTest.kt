package com.calypsan.listenup.web.features.discover

import com.calypsan.listenup.api.error.UnexpectedClientError
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.nav.Routes
import com.calypsan.listenup.web.nav.Route
import com.calypsan.listenup.web.nav.isServed
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import com.calypsan.listenup.web.design.WebAppSurface
import kotlinx.browser.window
import org.w3c.dom.HTMLElement

/** See all activity on the web (X33): `/discover/activity`, the whole feed in the section's own rows. */
class ActivityFeedPageTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun page(
            state: ActivityFeedUiState,
            onBack: () -> Unit = {},
            onRefresh: () -> Unit = {},
        ) = mounts.mount {
            ActivityFeedPage(
                state = state,
                nowMs = DISCOVER_NOW_MS,
                onBack = onBack,
                onRefresh = onRefresh,
                onOpenBook = {},
                onOpenShelf = {},
                onOpenProfile = {},
                onShelveOne = {},
            )
        }

        test("/discover/activity parses and prints") {
            Routes.discoverActivity.toUrl() shouldBe "/discover/activity"
            val parsed = Route.parse("/discover/activity")
            parsed.segments shouldBe listOf("discover", "activity")
            parsed.isServed() shouldBe true
            Route.parse("/discover/elsewhere").isServed() shouldBe false
        }

        test("the page is titled Activity and lists every row with its ⋯") {
            val host = page(feed(*Array(40) { act("a$it", "finished_book") }))
            host.querySelectorAll("h1").length shouldBe 1
            host.querySelector("h1")!!.textContent shouldBe "Activity"
            host.querySelectorAll(".disc-feed-row").length shouldBe 40
            host.querySelectorAll(".disc-feed-row [aria-haspopup=menu]").length shouldBe 40
            host.querySelectorAll(".disc-aside-extra").length shouldBe 0
        }

        test("loading is the section's skeleton, and a failure offers Try again") {
            page(ActivityFeedUiState.Loading).querySelectorAll(".disc-skel-rows").length shouldBe 1

            var retried = 0
            val failed = page(ActivityFeedUiState.Error(UnexpectedClientError()), onRefresh = { retried++ })
            (failed.querySelector(".disc-error") as HTMLElement).textContent shouldBe "Couldn't load activity"
            (failed.querySelector(".disc-retry") as HTMLElement).click()
            retried shouldBe 1
        }

        test("rows flow into width-driven columns, as on Android and iOS") {
            val host =
                mounts.mount {
                    WebAppSurface {
                        ActivityFeedPage(
                            state = feed(act("a1", "finished_book"), act("a2", "finished_book")),
                            nowMs = DISCOVER_NOW_MS,
                            onBack = {},
                            onRefresh = {},
                            onOpenBook = {},
                            onOpenShelf = {},
                            onOpenProfile = {},
                            onShelveOne = {},
                        )
                    }
                }
            val feed = host.querySelector(".disc-feed") as HTMLElement
            window.getComputedStyle(feed).display shouldBe "grid"
        }

        test("the breadcrumb leads back to Discover, and Refresh asks for anything newer") {
            var back = 0
            var refreshed = 0
            val host = page(feed(act("a1", "finished_book")), onBack = { back++ }, onRefresh = { refreshed++ })
            (host.querySelector(".crumb button, .crumb a") as HTMLElement).click()
            back shouldBe 1
            (host.querySelector(".page-h button") as HTMLElement).click()
            refreshed shouldBe 1
        }
    })
