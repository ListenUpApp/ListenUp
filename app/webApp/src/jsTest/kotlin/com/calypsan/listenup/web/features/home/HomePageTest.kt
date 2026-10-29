package com.calypsan.listenup.web.features.home

import com.calypsan.listenup.api.error.UnexpectedClientError
import com.calypsan.listenup.web.features.books.BookSelection
import com.calypsan.listenup.client.domain.GenreShare
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.presentation.home.HomeConnectivity
import com.calypsan.listenup.client.presentation.home.HomeSlot
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.client.presentation.home.TimeOfDay
import com.calypsan.listenup.web.MountRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.events.EventTarget
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

private fun EventTarget.press(key: String) {
    dispatchEvent(
        KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)),
    )
}

private fun HTMLElement.textOf(selector: String): String = (querySelector(selector) as? HTMLElement)?.textContent.orEmpty()

private fun HTMLElement.count(selector: String): Int = querySelectorAll(selector).length

private fun HTMLElement.buttonsLabelled(label: String): List<HTMLElement> {
    val buttons = querySelectorAll("button")
    return (0 until buttons.length)
        .map { buttons.item(it) as HTMLElement }
        .filter { it.textContent?.trim() == label }
}

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
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun homePage(
            state: HomeUiState,
            stats: HomeStatsUiState = HomeStatsUiState.Loading,
            actions: HomeActions = HomeActions.None,
            isWide: Boolean = false,
            selection: BookSelection? = null,
        ): HTMLElement =
            mounts.mount {
                HomePage(state, stats, actions, isWide = isWide, selection = selection)
            }

        test("the greeting is worded here from the time of day and the first name (H2)") {
            homePage(readyHome(timeOfDay = TimeOfDay.Evening, firstName = "Simon")).textOf(".page-t") shouldBe
                "Good evening, Simon"
            homePage(readyHome(timeOfDay = TimeOfDay.Morning, firstName = null)).textOf(".page-t") shouldBe
                "Good morning"
        }

        test("Home has no search field of its own: search is the shell's (H3)") {
            val host = homePage(readyHome())
            host.count(".home-search") shouldBe 0
            host.querySelectorAll("input").length shouldBe 0
            host.buttonsLabelled("Search your library").size shouldBe 0
        }

        test("a Continue Listening card shows the book's own title and time remaining") {
            val host =
                homePage(
                    readyHome(
                        everythingInProgress = listOf(continuing("b1", "The Institute", progress = 0.5f, totalHours = 10)),
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
                        everythingInProgress =
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
                    readyHome(everythingInProgress = listOf(continuing("b1", "The Institute"))),
                    actions = HomeActions.None.copy(onOpenBook = { opened = it }),
                )

            (host.querySelector(".home-card") as HTMLElement).click()

            opened shouldBe "b1"
        }

        test("a card is reachable by keyboard, not just by mouse") {
            var opened: String? = null
            val host =
                homePage(
                    readyHome(everythingInProgress = listOf(continuing("b1", "The Institute"))),
                    actions = HomeActions.None.copy(onOpenBook = { opened = it }),
                )

            (host.querySelector(".home-card") as HTMLElement).press("Enter")

            opened shouldBe "b1"
        }

        test("Everything in progress names each card once, with author, percent and time left (§2.5, §8.2)") {
            val host =
                homePage(readyHome(everythingInProgress = listOf(continuing("b1", "Babel", progress = 0.11f, totalHours = 21))))
            val card = host.querySelector(".home-card") as HTMLElement
            card.getAttribute("aria-label") shouldBe "Babel, by A. Writer, 11% listened, 18h 41m left"
            card.querySelector(".home-card-a")?.textContent shouldBe "A. Writer"
            (card.querySelector(".progress") as HTMLElement).getAttribute("aria-hidden") shouldBe "true"
        }

        test("the book in the player bar is the first card") {
            val host =
                homePage(readyHome(everythingInProgress = listOf(continuing("loaded", "The Loaded Book"), continuing("b1", "Babel"))))
            (host.querySelector(".home-card") as HTMLElement).getAttribute("aria-label").orEmpty() shouldStartWith "The Loaded Book"
        }

        test("the header says Everything in progress and In Library leaves for the Library's In progress") {
            var opened = false
            val host =
                homePage(
                    readyHome(everythingInProgress = listOf(continuing("b1", "Babel"))),
                    actions = HomeActions.None.copy(onOpenInProgress = { opened = true }),
                )
            host.textOf(".home-progress-h") shouldBe "Everything in progress"
            host.buttonsLabelled("In Library →").single().click()
            opened shouldBe true
        }

        test("only a wide page says who reads each book") {
            val narrated = continuing("b1", "Babel", narrators = "Chris Lew Kum Hoi")
            homePage(readyHome(everythingInProgress = listOf(narrated)), isWide = true).textOf(".home-card-n") shouldBe
                "Read by Chris Lew Kum Hoi"
            homePage(readyHome(everythingInProgress = listOf(narrated)), isWide = false).count(".home-card-n") shouldBe 0
        }

        test("nothing in progress means no Everything in progress section at all") {
            homePage(readyHome(everythingInProgress = emptyList())).count(".home-progress") shouldBe 0
        }

        test("at most six cards") {
            homePage(readyHome(everythingInProgress = (1..9).map { continuing("b$it", "Book $it") })).count(".home-card") shouldBe 6
        }

        // ── the blocks (§2.1, §3.4) ─────────────────────────────────────────────

        test("a slot on a wide page sits beside the stats, before Everything in progress (§3.4)") {
            val host =
                homePage(
                    readyHome(slot = finishedSlot(), everythingInProgress = listOf(continuing("b1", "Babel"))),
                    stats = weekStats(),
                    isWide = true,
                )
            val row = host.querySelector(".home-row") as HTMLElement
            row.querySelector(".home-hero") shouldNotBe null
            row.querySelector(".home-stats-card") shouldNotBe null
            val order = host.querySelectorAll(".home-row, .home-progress, .home-shelves-section")
            (0 until order.length).map { (order.item(it) as HTMLElement).className.split(" ").first() } shouldBe
                listOf("home-row", "home-section", "home-section")
        }

        test("a phone keeps the fixed order: the slot, Everything in progress, stats, shelves") {
            val host =
                homePage(
                    readyHome(slot = finishedSlot(), everythingInProgress = listOf(continuing("b1", "Babel"))),
                    stats = weekStats(),
                )
            host.count(".home-row") shouldBe 0
            val sections = host.querySelectorAll(".home-hero, .home-progress, .home-stats-card, .home-shelves-section")
            (0 until sections.length).map { index ->
                val classes = (sections.item(index) as HTMLElement).classList
                when {
                    classes.contains("home-hero") -> "slot"
                    classes.contains("home-progress") -> "progress"
                    classes.contains("home-stats-card") -> "stats"
                    else -> "shelves"
                }
            } shouldBe listOf("slot", "progress", "stats", "shelves")
        }

        test("a loaded book on a wide page sets stats beside shelves after Everything in progress") {
            val host =
                homePage(
                    readyHome(slot = HomeSlot.Absent, everythingInProgress = listOf(continuing("b1", "Babel"))),
                    stats = weekStats(),
                    isWide = true,
                )
            host.count(".home-hero") shouldBe 0
            (host.querySelector(".home-row") as HTMLElement).querySelector(".home-shelves-section") shouldNotBe null
        }

        test("books in progress and nothing loaded: no Resume hero, Everything in progress holds them, one book too") {
            val host = homePage(readyHome(slot = HomeSlot.Absent, everythingInProgress = listOf(continuing("b1", "Babel"))))
            host.count(".home-hero") shouldBe 0
            host.count(".home-card") shouldBe 1
        }

        test("nothing in progress offers a way into the library") {
            var opened = false
            homePage(
                readyHome(slot = HomeSlot.NothingInProgress(candidate = null), everythingInProgress = emptyList()),
                actions = HomeActions.None.copy(onOpenLibrary = { opened = true }),
            ).buttonsLabelled("Browse the Library").single().click()
            opened shouldBe true
        }

        // ── the two honesty guards ──────────────────────────────────────────────

        test("See all appears only with a shelf, and opens the My shelves list (H24)") {
            var seeAll = false
            homePage(
                readyHome(myShelves = listOf(shelf("Winter"))),
                actions = HomeActions.None.copy(onSeeAllShelves = { seeAll = true }),
            ).buttonsLabelled("See all").single().apply {
                getAttribute("aria-label") shouldBe "See all shelves"
                click()
            }
            seeAll shouldBe true

            homePage(readyHome(myShelves = emptyList())).buttonsLabelled("See all").size shouldBe 0
        }

        test("Select in the Everything in progress header starts selecting, and hides while selecting (H16)") {
            var started = false
            val idle = BookSelection(isSelecting = false, selectedIds = emptySet(), onToggle = {}, onStart = { started = true })
            homePage(readyHome(everythingInProgress = listOf(continuing("babel", "Babel"))), selection = idle)
                .buttonsLabelled("Select")
                .single()
                .click()
            started shouldBe true

            val page =
                homePage(
                    readyHome(everythingInProgress = listOf(continuing("babel", "Babel"), continuing("dcc", "DCC"))),
                    selection = selecting(selected = setOf("babel")),
                )
            page.querySelectorAll("[role=checkbox]").asList().map { (it as HTMLElement).getAttribute("aria-checked") } shouldBe
                listOf("true", "false")
            page.buttonsLabelled("Select").size shouldBe 0
        }

        test("a Home that cannot select offers no Select") {
            homePage(readyHome(everythingInProgress = listOf(continuing("babel", "Babel")))).buttonsLabelled("Select").size shouldBe
                0
        }

        test("every shelf is rendered, now that each one opens") {
            // This spec used to assert the opposite: Home withheld the section entirely while there
            // was no shelf screen, because a row that cannot be opened is worse than an absent one.
            // The screen exists, so the honesty guard flips rather than being deleted.
            val host = homePage(readyHome(myShelves = listOf(shelf("Finished"), shelf("Want to Listen"))))

            host.textContent.orEmpty() shouldContain "Finished"
            host.textContent.orEmpty() shouldContain "Want to Listen"
            host.count(".home-shelf") shouldBe 2
        }

        test("opening a shelf reports that shelf, not the first one on the row") {
            var opened: String? = null
            val host =
                homePage(
                    readyHome(myShelves = listOf(shelf("Finished"), shelf("Want to Listen"))),
                    actions = HomeActions.None.copy(onOpenShelf = { opened = it }),
                )

            (host.querySelectorAll(".home-shelf").item(1) as HTMLElement).click()

            opened shouldBe "shelf-Want to Listen"
        }

        test("a shelf card is a mosaic, its name, and how many books and how long") {
            val host = homePage(readyHome(myShelves = listOf(shelf("Winter reads", books = 6, seconds = 266_400L))))
            host.count(".home-shelf .mosaic-tile") shouldBe 4
            host.textOf(".home-shelf-sub") shouldBe "6 books · 74h"
        }

        test("a private shelf says so to everyone") {
            val host =
                homePage(readyHome(myShelves = listOf(shelf("Road trip", books = 1, seconds = 0L, isPrivate = true))))
            (host.querySelector(".home-shelf-lock") as HTMLElement).getAttribute("aria-label") shouldBe "Private"
            host.textOf(".home-shelf-sub") shouldBe "1 book"
        }

        test("no shelves is an invitation beside New shelf") {
            val host = homePage(readyHome(myShelves = emptyList()))
            host.textContent.orEmpty() shouldContain
                "A shelf is your own list of audiobooks — a series, a mood, a plan for the winter."
            host.buttonsLabelled("New shelf").size shouldBe 1
            host.count(".home-shelf") shouldBe 0
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

        // ⛔ "Today" is sharedUI's `home_today` verbatim: Compose resources do not reach Compose HTML.
        test("the last bar is labelled Today, and only the last (§2.6)") {
            val labels = homePage(readyHome(), stats = weekStats()).querySelectorAll(".home-bar-label")
            (labels.item(labels.length - 1) as HTMLElement).textContent shouldBe "Today"
            (0 until labels.length - 1).count { (labels.item(it) as HTMLElement).textContent == "Today" } shouldBe 0
        }

        test("a week under an hour totals in minutes alone, never 0h") {
            homePage(readyHome(), stats = weekStats(todaySeconds = 0, peakSeconds = 2_400))
                .textOf(".home-stats-total") shouldBe "40m"
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

        test("a live streak is a chip, and the best is in the footer (§2.6)") {
            val host = homePage(readyHome(), stats = weekStats(currentStreakDays = 5, longestStreakDays = 14))
            host.textOf(".home-streak-chip") shouldBe "5-day streak"
            host.textOf(".home-stats-best") shouldBe "Best streak 14 days"
        }

        test("a lapsed streak is only the best, and never a 0-day streak") {
            val host = homePage(readyHome(), stats = weekStats(currentStreakDays = 0, longestStreakDays = 14))
            host.count(".home-streak-chip") shouldBe 0
            host.textContent.orEmpty() shouldNotContain "0-day"
            host.textOf(".home-stats-best") shouldBe "Best streak 14 days"
        }

        test("no streak says nothing about streaks") {
            homePage(readyHome(), stats = weekStats(currentStreakDays = 0, longestStreakDays = 0))
                .textContent
                .orEmpty() shouldNotContain "streak"
        }

        test("the stats heading is the window it counts, inside the card") {
            homePage(readyHome(), stats = weekStats()).textOf(".home-stats-h") shouldBe "Your last 7 days"
        }

        test("the chart is one image whose words list every day, ending with today") {
            val chart = homePage(readyHome(), stats = weekStats(todaySeconds = 60)).querySelector(".home-chart") as HTMLElement
            chart.getAttribute("role") shouldBe "img"
            chart.getAttribute("aria-label")!! shouldStartWith "Listening per day, oldest to today: "
            chart.getAttribute("aria-label")!! shouldEndWith "today 1m"
        }

        test("the leaderboard link leaves for Discover's leaderboard") {
            var opened = false
            homePage(readyHome(), stats = weekStats(), actions = HomeActions.None.copy(onOpenLeaderboard = { opened = true }))
                .buttonsLabelled("7-day leaderboard →")
                .single()
                .click()
            opened shouldBe true
        }

        // ── stats states ────────────────────────────────────────────────────────

        test("stats that have never loaded show a placeholder, not a zeroed week") {
            val host = homePage(readyHome(), stats = HomeStatsUiState.Loading)

            host.count(".home-stats-skel") shouldBe 1
            host.count(".home-bar") shouldBe 0
        }

        test("a user who has never listened is told so, rather than shown an empty chart") {
            val host = homePage(readyHome(), stats = HomeStatsUiState.Empty)

            host.textContent.orEmpty() shouldContain "fill in here after your first book"
            host.count(".home-bar") shouldBe 0
        }

        test("failing stats do not take the rest of Home down with them") {
            val host =
                homePage(
                    readyHome(everythingInProgress = listOf(continuing("b1", "The Institute"))),
                    stats = HomeStatsUiState.Error(isRetryable = true),
                )

            host.textContent.orEmpty() shouldContain "Couldn't load your stats."
            // The row someone opened Home for is still there.
            host.textOf(".home-card-t") shouldBe "The Institute"
        }

        test("failed stats offer Try again, and pressing it retries the stats alone") {
            // Before this the section promised it would fix itself, and nothing ever re-ran it.
            var statsRetries = 0
            var homeRetries = 0
            val host =
                homePage(
                    readyHome(),
                    stats = HomeStatsUiState.Error(isRetryable = true),
                    actions = HomeActions.None.copy(onRetry = { homeRetries++ }, onRetryStats = { statsRetries++ }),
                )

            val retries = host.buttonsLabelled("Try again")
            retries.size shouldBe 1
            retries.single().click()

            statsRetries shouldBe 1
            homeRetries shouldBe 0
        }

        test("stats that cannot be retried offer nothing to press") {
            val host = homePage(readyHome(), stats = HomeStatsUiState.Error(isRetryable = false))

            host.textContent.orEmpty() shouldNotContain "Try again"
        }

        test("a failed Home offers Try again, and pressing it asks Home to reload") {
            var retries = 0
            val host =
                homePage(
                    HomeUiState.Error(UnexpectedClientError(), TimeOfDay.Afternoon),
                    actions = HomeActions.None.copy(onRetry = { retries++ }),
                )

            val retry = host.buttonsLabelled("Try again")
            retry.size shouldBe 1
            retry.single().click()

            retries shouldBe 1
        }

        // ── the library-still-arriving strip ────────────────────────────────────

        test("a running scan speaks Home's words, with its grouped count and how far along it is") {
            val host = homePage(readyHome(scanProgress = scanning(books = 412, booksTotal = 1_280)))

            host.textOf(".lib-status-t") shouldBe "Building your library · 412 of 1,280 books"
            host.textOf(".lib-status-sub") shouldBe
                "Your place in each book is already here; covers and titles are arriving."
            (host.querySelector(".lib-status-track") as HTMLElement).getAttribute("aria-valuenow") shouldBe "32"
        }

        test("one strip, even with a scan and a first seed at once") {
            val host = homePage(readyHome(isBuildingInitialLibrary = true, scanProgress = scanning()))

            host.count(".lib-status") shouldBe 1
        }

        test("a first seed with no scan says Home's title alone, with no count and no ellipsis") {
            val host = homePage(readyHome(isBuildingInitialLibrary = true))

            host.textOf(".lib-status-t") shouldBe "Building your library"
        }

        test("the strip sits under the offline notice and above the slot (H5)") {
            val host =
                homePage(
                    readyHome(
                        slot = finishedSlot(),
                        scanProgress = scanning(),
                        connectivity = HomeConnectivity.ServerUnreachable,
                    ),
                )
            val order =
                host.querySelectorAll(".home-notice, .lib-status, .home-hero").asList().map {
                    (it as HTMLElement).className
                }
            order.map { it.substringBefore(' ') } shouldBe listOf("home-notice", "lib-status", "home-hero")
        }

        test("offline and a failed Continue Listening show together") {
            val host =
                homePage(
                    readyHome(connectivity = HomeConnectivity.Offline, continueListeningError = UnexpectedClientError()),
                )
            host.count(".home-notice") shouldBe 1
            host.count(".home-cl-failed") shouldBe 1
        }

        test("isSyncing alone says NOTHING, because it is false for the whole initial seed") {
            // The connection is `Connected` throughout a first seed, so `isSyncing` is false
            // exactly when thousands of books are arriving. A strip driven from it would be silent
            // during the one window it exists for — and chatty during routine background passes
            // that need no explanation. See LibraryUiState.Loaded's identical warning.
            val host = homePage(readyHome(isSyncing = true, isBuildingInitialLibrary = false))

            host.count(".lib-status") shouldBe 0
        }

        test("a quiet library says nothing at all") {
            val host = homePage(readyHome())

            host.count(".lib-status") shouldBe 0
        }

        // ── the other two page states ───────────────────────────────────────────

        test("a Home that has not answered yet does not claim anything about the library") {
            val host = homePage(HomeUiState.Loading)

            host.count(".home-continue") shouldBe 0
            host.textContent.orEmpty() shouldNotContain "Nothing on the go"
        }

        test("a failed Home keeps the greeting and says plainly that Home could not load") {
            val host = homePage(HomeUiState.Error(UnexpectedClientError(), TimeOfDay.Evening))

            host.textOf(".page-t") shouldBe "Good evening"
            host.textContent.orEmpty() shouldContain "We couldn't load Home."
        }

        // ── first sync, loading, the server notice, the failures and the stats states ─────────

        test("first sync: a status strip in Home's words over a skeleton slot, with no Resume") {
            val page = homePage(readyHome(slot = HomeSlot.FirstSync, isLibraryArriving = true))
            val strip = page.querySelector(".lib-status") as HTMLElement
            strip.getAttribute("role") shouldBe "status"
            strip.textContent.orEmpty() shouldContain "Your place in each book is already here; covers and titles are arriving."
            page.count(".lib-status") shouldBe 1
            page.querySelector("[aria-label='Up next, loading']") shouldNotBe null
            page.count("[aria-label='Up next, loading'] button") shouldBe 0
        }

        test("page loading draws the slot and rail skeletons and no strip") {
            val page = homePage(HomeUiState.Loading)
            page.querySelector("[aria-label='Up next, loading']") shouldNotBe null
            page.querySelector("[aria-label='Loading books']") shouldNotBe null
            page.count(".lib-status") shouldBe 0
            page.count("[aria-label='Up next, loading'] button") shouldBe 0
        }

        test("unreachable or offline: one status notice with Retry") {
            listOf(HomeConnectivity.ServerUnreachable, HomeConnectivity.Offline).forEach { connectivity ->
                var retried = false
                val page =
                    homePage(
                        readyHome(connectivity = connectivity),
                        actions = HomeActions.None.copy(onRetryConnection = { retried = true }),
                    )
                val notice = page.querySelector(".home-notice") as HTMLElement
                notice.getAttribute("role") shouldBe "status"
                notice.textContent.orEmpty() shouldContain "Can't reach your server."
                notice.textContent.orEmpty() shouldContain "Your library is here; playing needs the connection."
                page.buttonsLabelled("Retry").single().click()
                retried shouldBe true
                mounts.disposeAll()
            }
        }

        test("while the library arrives, My shelves loads rather than inviting a first shelf") {
            val page = homePage(readyHome(slot = HomeSlot.FirstSync, isLibraryArriving = true))
            page.querySelector("[aria-label='Loading shelves']") shouldNotBe null
            page.textContent.orEmpty() shouldNotContain "A shelf is your own list"
        }

        test("online, there is no notice") {
            homePage(readyHome()).count(".home-notice") shouldBe 0
        }

        test("a failed Continue Listening is one line with Try again (web gains it)") {
            var retried = false
            val page =
                homePage(
                    readyHome(continueListeningError = UnexpectedClientError()),
                    actions = HomeActions.None.copy(onRetry = { retried = true }),
                )
            val line = page.querySelector(".home-cl-failed") as HTMLElement
            line.textContent.orEmpty() shouldContain "Couldn't load what you're listening to"
            page.buttonsLabelled("Try again").single().click()
            retried shouldBe true
        }

        test("stats: a one-line invitation for a new listener, and an error with Try again") {
            val empty = homePage(readyHome(), stats = HomeStatsUiState.Empty)
            empty.textOf(".home-stats-invite") shouldBe
                "Your listening time, streak and favourite genres fill in here after your first book."
            empty.count(".home-chart") shouldBe 0
            mounts.disposeAll()
            val failed = homePage(readyHome(), stats = HomeStatsUiState.Error(isRetryable = true))
            failed.textContent.orEmpty() shouldContain "Couldn't load your stats."
            failed.buttonsLabelled("Try again").size shouldBe 1
        }

        // ⛔ While selecting, a press PICKS instead of opening. One gesture, two jobs, decided by
        // the mode — the same rule the library grid follows, now that Home is selectable too.
        test("a continue card opens the book when nothing is being selected") {
            var opened: String? = null
            val host = homePage(withOneBook(), actions = HomeActions.None.copy(onOpenBook = { opened = it }), selection = null)

            (host.querySelector(".home-card") as HTMLElement).click()

            opened shouldBe CONTINUE_BOOK_ID
        }

        // ⛔ The state production is in whenever nobody is selecting: the scaffold ALWAYS supplies a
        // BookSelection, with `isSelecting = false`. A `null` selection never reaches these cards at
        // runtime, so testing only null-versus-active left the ordinary case uncovered — and a
        // sabotage that made any present selection pick on every press broke no test at all.
        test("a selection that is present but idle still opens the book") {
            var opened: String? = null
            val picked = mutableListOf<String>()
            val host =
                homePage(
                    withOneBook(),
                    actions = HomeActions.None.copy(onOpenBook = { opened = it }),
                    selection = idleSelection(onToggle = { picked += it }),
                )

            (host.querySelector(".home-card") as HTMLElement).click()

            opened shouldBe CONTINUE_BOOK_ID
            picked shouldBe emptyList()
        }

        test("while selecting, a press picks the book rather than opening it") {
            var opened: String? = null
            val picked = mutableListOf<String>()
            val host =
                homePage(
                    withOneBook(),
                    actions = HomeActions.None.copy(onOpenBook = { opened = it }),
                    selection = selecting(onToggle = { picked += it }),
                )

            (host.querySelector(".home-card") as HTMLElement).click()

            picked shouldBe listOf(CONTINUE_BOOK_ID)
            opened shouldBe null
        }

        test("a picked card is marked, and an unpicked one is not") {
            val picked = homePage(withOneBook(), selection = selecting(selected = setOf(CONTINUE_BOOK_ID)))
            val unpicked = homePage(withOneBook(), selection = selecting(selected = emptySet()))

            (picked.querySelector(".home-card") as HTMLElement).className shouldContain "is-sel"
            (unpicked.querySelector(".home-card") as HTMLElement).className shouldNotContain "is-sel"
        }

        // The keyboard path is not a second implementation — it presses the same way.
        test("Enter picks while selecting, just as a click does") {
            val picked = mutableListOf<String>()
            val host = homePage(withOneBook(), selection = selecting(onToggle = { picked += it }))

            (host.querySelector(".home-card") as HTMLElement)
                .dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = "Enter", bubbles = true)))

            picked shouldBe listOf(CONTINUE_BOOK_ID)
        }
    })

private fun idleSelection(onToggle: (String) -> Unit = {}) =
    BookSelection(isSelecting = false, selectedIds = emptySet(), onToggle = onToggle, onStart = {})

private fun selecting(
    selected: Set<String> = emptySet(),
    onToggle: (String) -> Unit = {},
) = BookSelection(isSelecting = true, selectedIds = selected, onToggle = onToggle, onStart = {})

/** A Home with exactly one Continue Listening card, which is what the selection specs press. */
private fun withOneBook() = readyHome(everythingInProgress = listOf(continuing(CONTINUE_BOOK_ID, "Elantris")))

private const val CONTINUE_BOOK_ID = "b7"
