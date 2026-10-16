package com.calypsan.listenup.web.features.discover

import com.calypsan.listenup.client.presentation.discover.LeaderboardUiState
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/** The Community aside: the leaderboard and the activity feed. */
class CommunityAsideTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        test("period and category are two radio groups, visible while the board is loading") {
            val host = mounts.mount { discoverPage(leaderboard = LeaderboardUiState.Loading) }
            val groups = host.querySelectorAll("[role=radiogroup]").asList().map { (it as HTMLElement).getAttribute("aria-label") }
            groups shouldBe listOf("Leaderboard period", "Rank by")
            host.querySelectorAll("[role=radiogroup]").item(0)!!.textContent shouldBe "WeekMonthYearAll time"
        }

        test("a rank is read as Rank N before the name, and podium ranks wear medals") {
            val host = mounts.mount { discoverPage(leaderboard = board(leaders())) }
            val first = host.querySelector(".disc-lb-row a") as HTMLElement
            first.textContent.orEmpty().startsWith("Rank 1") shouldBe true
            host.querySelectorAll(".disc-rank.is-medal").length shouldBe 3
        }

        test("your row reads You on the tinted fill") {
            val host = mounts.mount { discoverPage(leaderboard = board(leaders(youAt = 2))) }
            val you = host.querySelector(".disc-lb-row.is-you") as HTMLElement
            you.textContent.orEmpty() shouldContain "You"
            you.textContent.orEmpty() shouldNotContain "Person 2"
        }

        test("every row is a link to the person, with their avatar") {
            val host = mounts.mount { discoverPage(leaderboard = board(leaders())) }
            val links = host.querySelectorAll(".disc-lb-row a").asList().map { it as HTMLElement }
            links.first().getAttribute("href") shouldBe "/profile/u1"
            links.all { it.querySelector(".uav") != null } shouldBe true
        }

        test("six rows are rendered, the sixth marked desktop-only, then Show all 9 expands") {
            val host = mounts.mount { discoverPage(leaderboard = board(leaders())) }
            host.querySelectorAll(".disc-lb-row").length shouldBe 6
            host.querySelectorAll(".disc-lb-row.disc-aside-extra").length shouldBe 1
            val showAll = host.querySelector(".disc-show-all") as HTMLElement
            showAll.textContent shouldBe "Show all 9"
            showAll.click()
            awaitFrame()
            host.querySelectorAll(".disc-lb-row").length shouldBe 9
            host.querySelectorAll(".disc-lb-row.disc-aside-extra").length shouldBe 0
        }

        test("at exactly six leaders the toggle is for phones only") {
            val host = mounts.mount { discoverPage(leaderboard = board(leaders(count = 6))) }
            (host.querySelector(".disc-show-all") as HTMLElement).classList.contains("disc-phone-only") shouldBe true
        }
    })
