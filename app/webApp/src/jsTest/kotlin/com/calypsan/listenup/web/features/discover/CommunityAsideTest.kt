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

        test("the heading is Activity, and a row's avatar is a link named for the person") {
            val host = mounts.mount { discoverPage(activity = feed(act("a1", "finished_book"))) }
            host.querySelectorAll(".disc-section-h").asList().any { it.textContent == "Activity" } shouldBe true
            val avatar = host.querySelector(".disc-feed-avatar") as HTMLElement
            avatar.tagName shouldBe "A"
            avatar.getAttribute("aria-label") shouldBe "Ada's profile"
            avatar.getAttribute("href") shouldBe "/profile/u1"
        }

        test("the sentence names the book as a link, ends by its author, and shows the cover") {
            val host = mounts.mount { discoverPage(activity = feed(act("a1", "listening_session"))) }
            val line = host.querySelector(".disc-feed-line") as HTMLElement
            line.textContent shouldBe "Ada listened to 48 minutes of The Institute by Stephen King"
            (line.querySelector("a[href='/book/b1']") as HTMLElement).textContent shouldBe "The Institute"
            host.querySelectorAll(".disc-feed-cover").length shouldBe 1
        }

        test("a created shelf links the shelf") {
            var opened: String? = null
            val host =
                mounts.mount {
                    discoverPage(
                        activity = feed(act("a1", "shelf_created", bookId = null, shelfId = "sh1", shelfName = "Sci-fi")),
                        onOpenShelf = { opened = it },
                    )
                }
            val link = host.querySelector(".disc-feed-line a[href='/shelf/sh1']") as HTMLElement
            link.textContent shouldBe "Sci-fi"
            link.click()
            opened shouldBe "sh1"
            host.querySelectorAll(".disc-feed-cover").length shouldBe 0
        }

        test("your own row reads You, and its avatar is Your profile") {
            val host = mounts.mount { discoverPage(activity = feed(act("a1", "finished_book", isYou = true))) }
            (host.querySelector(".disc-feed-line") as HTMLElement).textContent.orEmpty().startsWith("You finished") shouldBe true
            (host.querySelector(".disc-feed-avatar") as HTMLElement).getAttribute("aria-label") shouldBe "Your profile"
        }

        test("eight rows render, the last two desktop-only; See all activity shows the rest") {
            val rows = (1..12).map { act("a$it", "finished_book") }.toTypedArray()
            val host = mounts.mount { discoverPage(activity = feed(*rows)) }
            host.querySelectorAll(".disc-feed-row").length shouldBe 8
            host.querySelectorAll(".disc-feed-row.disc-aside-extra").length shouldBe 2
            val more = host.querySelector(".disc-see-all") as HTMLElement
            more.textContent shouldBe "See all activity →"
            more.click()
            awaitFrame()
            host.querySelectorAll(".disc-feed-row").length shouldBe 12
            (host.querySelector(".disc-see-all") as HTMLElement).textContent shouldBe "Show less"
        }
    })
