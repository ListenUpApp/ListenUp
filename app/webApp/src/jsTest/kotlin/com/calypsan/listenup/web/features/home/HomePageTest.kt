package com.calypsan.listenup.web.features.home

import com.calypsan.listenup.client.domain.GenreShare
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import com.calypsan.listenup.client.presentation.home.HomeUiState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.EventTarget
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

private fun homePage(
    state: HomeUiState,
    stats: HomeStatsUiState = HomeStatsUiState.Loading,
    onOpenBook: (String) -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenLibrary: () -> Unit = {},
): HTMLElement {
    val root = document.createElement("div") as HTMLElement
    document.body?.appendChild(root)
    renderComposable(root = root) {
        HomePage(
            state = state,
            stats = stats,
            onOpenBook = onOpenBook,
            onOpenSearch = onOpenSearch,
            onOpenLibrary = onOpenLibrary,
        )
    }
    return root
}

private fun EventTarget.press(key: String) {
    dispatchEvent(
        KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)),
    )
}

private fun HTMLElement.textOf(selector: String): String = (querySelector(selector) as? HTMLElement)?.textContent.orEmpty()

private fun HTMLElement.count(selector: String): Int = querySelectorAll(selector).length

private const val DAYS_IN_WEEK = 7

/** Buckets the [weekStats] fixture leaves at zero: every day but today and the peak. */
private const val SILENT_DAYS = 5

/**
 * Home rendered against fixed sessions.
 *
 * What these pin, beyond "it renders": the two things the design sheet asks for that the data does
 * not back are NOT rendered, the week chart draws today in the right place, and a state that is
 * still loading never claims the library is empty.
 */
class HomePageTest :
    FunSpec({

        test("the greeting is the ViewModel's, not one assembled in the page") {
            val host = homePage(readyHome(userName = "Simon", timeGreeting = "Good evening"))

            host.textOf(".home-greet") shouldBe "Good evening, Simon"
        }

        test("a Continue Listening card shows the book's own title and time remaining") {
            val host =
                homePage(
                    readyHome(
                        continueListening = listOf(continuing("b1", "The Institute", progress = 0.5f, totalHours = 10)),
                    ),
                )

            host.textOf(".home-card-t") shouldBe "The Institute"
            // Five of ten hours left, formatted by the shared domain helper rather than in here.
            host.textOf(".home-card-sub") shouldContain "5h"
        }

        test("an unhydrated slot keeps its place as a skeleton instead of collapsing the row") {
            // The position row arrived before the book did. Dropping it would shrink the row and
            // then grow it again seconds later, mid-sync.
            val host =
                homePage(
                    readyHome(
                        continueListening =
                            listOf(
                                continuing("b1", "The Institute"),
                                ContinueListeningItem.Loading("b2"),
                            ),
                    ),
                )

            host.count(".home-card") shouldBe 2
            host.count(".home-card.is-loading") shouldBe 1
        }

        test("clicking a card opens that book") {
            var opened: String? = null
            val host =
                homePage(
                    readyHome(continueListening = listOf(continuing("b1", "The Institute"))),
                    onOpenBook = { opened = it },
                )

            (host.querySelector(".home-card") as HTMLElement).click()

            opened shouldBe "b1"
        }

        test("a card is reachable by keyboard, not just by mouse") {
            var opened: String? = null
            val host =
                homePage(
                    readyHome(continueListening = listOf(continuing("b1", "The Institute"))),
                    onOpenBook = { opened = it },
                )

            (host.querySelector(".home-card") as HTMLElement).press("Enter")

            opened shouldBe "b1"
        }

        test("nothing on the go offers a way into the library rather than a dead end") {
            var browsed = false
            val host = homePage(readyHome(continueListening = emptyList()), onOpenLibrary = { browsed = true })

            (host.querySelector(".empty .btn") as HTMLElement).click()

            browsed shouldBe true
        }

        test("the search affordance leaves as the caller's event") {
            var searched = false
            val host = homePage(readyHome(), onOpenSearch = { searched = true })

            (host.querySelector(".home-search") as HTMLElement).click()

            searched shouldBe true
        }

        // ── the two honesty guards ──────────────────────────────────────────────

        test("there is no See all anywhere, because there is nowhere for it to go") {
            val host =
                homePage(
                    readyHome(
                        continueListening = listOf(continuing("b1", "The Institute")),
                        myShelves = listOf(shelf("Finished")),
                    ),
                    stats = weekStats(),
                )

            host.textContent.orEmpty() shouldNotContain "See all"
        }

        test("shelves are not rendered at all while web has no shelf screen to open") {
            // The state carries them and the design sheet draws them; a row that cannot be opened
            // is worse than an absent section, so Home leaves them out until the screen exists.
            val host = homePage(readyHome(myShelves = listOf(shelf("Finished"), shelf("Want to Listen"))))

            host.textContent.orEmpty() shouldNotContain "Finished"
            host.textContent.orEmpty() shouldNotContain "Want to Listen"
        }

        // ── the week chart ──────────────────────────────────────────────────────

        test("today is the LAST bar of the week and is the accented one") {
            val host = homePage(readyHome(), stats = weekStats())

            host.count(".home-bar") shouldBe DAYS_IN_WEEK
            val bars = host.querySelectorAll(".home-bar")
            val last = bars.item(bars.length - 1) as HTMLElement
            last.className shouldContain "is-today"
            (bars.item(0) as HTMLElement).className shouldNotContain "is-today"
        }

        test("a day with no listening still draws, so the week reads as seven days") {
            val host = homePage(readyHome(), stats = weekStats())

            // Five of the seven buckets are zero in the fixture.
            host.count(".home-bar.is-empty") shouldBe SILENT_DAYS
        }

        test("genre bars are percentages of the genres shown") {
            val host =
                homePage(
                    readyHome(),
                    stats =
                        weekStats(
                            topGenres = listOf(GenreShare("Fiction", 3), GenreShare("Sci-Fi", 1)),
                        ),
                )

            host.textOf(".home-genre .home-genre-pct") shouldBe "75%"
        }

        test("the streak section is absent when there is no streak to report") {
            val host = homePage(readyHome(), stats = weekStats(currentStreakDays = 0, longestStreakDays = 0))

            host.count(".home-streak") shouldBe 0
        }

        // ── stats states ────────────────────────────────────────────────────────

        test("stats that have never loaded show a placeholder, not a zeroed week") {
            val host = homePage(readyHome(), stats = HomeStatsUiState.Loading)

            host.count(".home-stats-skel") shouldBe 1
            host.count(".home-bar") shouldBe 0
        }

        test("a user who has never listened is told so, rather than shown an empty chart") {
            val host = homePage(readyHome(), stats = HomeStatsUiState.Empty)

            host.textContent.orEmpty() shouldContain "No listening yet"
            host.count(".home-bar") shouldBe 0
        }

        test("failing stats do not take the rest of Home down with them") {
            val host =
                homePage(
                    readyHome(continueListening = listOf(continuing("b1", "The Institute"))),
                    stats = HomeStatsUiState.Error(isRetryable = true),
                )

            host.textContent.orEmpty() shouldContain "Stats are unavailable"
            // The row someone opened Home for is still there.
            host.textOf(".home-card-t") shouldBe "The Institute"
        }

        // ── the library-still-arriving strip ────────────────────────────────────

        test("a running scan says what it is doing and how far along it is") {
            val host = homePage(readyHome(scanProgress = scanning(books = 40, booksTotal = 100)))

            host.textOf(".home-status-t") shouldBe "Analyzing"
            (host.querySelector(".home-status-fill") as HTMLElement).style.width shouldBe "40%"
        }

        test("a scan outranks the initial seed, because only the scan can say how far along it is") {
            val host = homePage(readyHome(isBuildingInitialLibrary = true, scanProgress = scanning()))

            host.count(".home-status") shouldBe 1
            host.textContent.orEmpty() shouldNotContain "Building your library"
        }

        test("a first seed with no scan still explains the short shelf") {
            val host = homePage(readyHome(isBuildingInitialLibrary = true))

            host.textContent.orEmpty() shouldContain "Building your library"
        }

        test("isSyncing alone says NOTHING, because it is false for the whole initial seed") {
            // The connection is `Connected` throughout a first seed, so `isSyncing` is false
            // exactly when thousands of books are arriving. A strip driven from it would be silent
            // during the one window it exists for — and chatty during routine background passes
            // that need no explanation. See LibraryUiState.Loaded's identical warning.
            val host = homePage(readyHome(isSyncing = true, isBuildingInitialLibrary = false))

            host.count(".home-status") shouldBe 0
        }

        test("a quiet library says nothing at all") {
            val host = homePage(readyHome())

            host.count(".home-status") shouldBe 0
        }

        // ── the other two page states ───────────────────────────────────────────

        test("a Home that has not answered yet does not claim anything about the library") {
            val host = homePage(HomeUiState.Loading)

            host.count(".home-continue") shouldBe 0
            host.textContent.orEmpty() shouldNotContain "Nothing on the go"
        }

        test("a failed Home surfaces the ViewModel's message rather than a generic apology") {
            val host = homePage(HomeUiState.Error("Failed to load home screen"))

            host.textContent.orEmpty() shouldContain "Failed to load home screen"
        }
    })
