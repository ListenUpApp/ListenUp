package com.calypsan.listenup.web.features.discover

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardEntry
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardSnapshot
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.ActivityUiModel
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiSession
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverUiBook
import com.calypsan.listenup.client.presentation.discover.LeaderboardUiState
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiBook
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement

/** A day in milliseconds — far enough back that the relative time is stable to read. */
private const val TWO_DAYS_MS = 172_800_000L

private const val NOW_MS = 1_000_000_000L

private val mountedHosts = mutableListOf<HTMLElement>()

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    mountedHosts += host
    renderComposable(root = host) { content() }
    return host
}

private fun listener(
    isLive: Boolean,
    lastActiveAt: Long = NOW_MS,
) = CurrentlyListeningUiSession(
    sessionId = "s1",
    userId = "u1",
    bookId = "b1",
    bookTitle = "The Institute",
    authorName = "Stephen King",
    coverPath = null,
    coverHash = null,
    displayName = "Ada",
    lastActiveAt = lastActiveAt,
    isLive = isLive,
)

private fun entry(
    name: String,
    rank: Int = 1,
    totalSeconds: Long = 0,
    booksFinished: Int = 0,
    currentStreakDays: Int = 0,
    longestStreakDays: Int = 0,
) = LeaderboardEntry(
    rank = rank,
    userId = "u-$name",
    displayName = name,
    totalSeconds = totalSeconds,
    booksFinished = booksFinished,
    currentStreakDays = currentStreakDays,
    longestStreakDays = longestStreakDays,
)

private fun activity(
    type: String,
    bookId: String? = "b1",
) = ActivityUiModel(
    id = "a1",
    userId = "u1",
    type = type,
    occurredAt = NOW_MS,
    userDisplayName = "Ada",
    bookId = bookId,
    bookTitle = "The Institute",
    bookAuthorName = "Stephen King",
    bookCoverPath = null,
    isReread = false,
    durationMs = 0L,
    milestoneValue = 7,
    milestoneUnit = null,
    shelfId = null,
    shelfName = null,
)

@Composable
private fun page(
    books: DiscoverBooksUiState = DiscoverBooksUiState.Loading,
    recentlyAdded: RecentlyAddedUiState = RecentlyAddedUiState.Loading,
    currentlyListening: CurrentlyListeningUiState = CurrentlyListeningUiState.Loading,
    leaderboard: LeaderboardUiState = LeaderboardUiState.Loading,
    activityState: ActivityFeedUiState = ActivityFeedUiState.Loading,
    nowMs: Long = NOW_MS,
    onOpenBook: (String) -> Unit = {},
    onSelectPeriod: (LeaderboardPeriod) -> Unit = {},
    onSelectCategory: (LeaderboardCategory) -> Unit = {},
) {
    DiscoverPage(
        books = books,
        recentlyAdded = recentlyAdded,
        currentlyListening = currentlyListening,
        leaderboard = leaderboard,
        activity = activityState,
        nowMs = nowMs,
        onOpenBook = onOpenBook,
        onSelectPeriod = onSelectPeriod,
        onSelectCategory = onSelectCategory,
    )
}

/**
 * Discover's own contract: that each section renders independently, that the social markers say
 * the right thing, and that the leaderboard's controls do what they appear to.
 *
 * The sentences, stats and rankings themselves are pinned in shared specs
 * (`ActivityProjectionsTest`, `LeaderboardProjectionsTest`) — this file is about the page.
 */
class DiscoverPageTest :
    FunSpec({

        afterSpec {
            mountedHosts.forEach { it.remove() }
            mountedHosts.clear()
        }

        test("every section is announced, even before any of them has data") {
            // The page's shape must not depend on which upstreams answered first, or Discover
            // reflows under the reader as each section lands.
            val host = mount { page() }

            val headings = host.querySelectorAll(".disc-section-h")
            headings.length shouldBe 5
            host.textContent.orEmpty() shouldContain "What others are listening to"
            host.textContent.orEmpty() shouldContain "Leaderboard"
        }

        test("one section failing costs exactly that section") {
            // The whole reason each section owns its own sealed state.
            val host =
                mount {
                    page(
                        leaderboard = LeaderboardUiState.Error(isRetryable = true),
                        currentlyListening = CurrentlyListeningUiState.Ready(listOf(listener(isLive = true))),
                    )
                }

            host.querySelectorAll(".disc-error").length shouldBe 1
            host.querySelectorAll(".disc-listener").length shouldBe 1
        }

        test("a live listener is marked as listening now, not as a stale timestamp") {
            val host =
                mount { page(currentlyListening = CurrentlyListeningUiState.Ready(listOf(listener(isLive = true)))) }

            host.querySelector(".disc-live")!!.textContent shouldBe "Listening now"
            host.querySelector(".disc-when") shouldBe null
        }

        test("someone who has stopped is shown on when they last played") {
            val host =
                mount {
                    page(
                        currentlyListening =
                            CurrentlyListeningUiState.Ready(
                                listOf(listener(isLive = false, lastActiveAt = NOW_MS - TWO_DAYS_MS)),
                            ),
                    )
                }

            host.querySelector(".disc-live") shouldBe null
            host.querySelector(".disc-when")!!.textContent shouldContain "2 days ago"
        }

        test("the selected period and category are the ones marked on, for a screen reader too") {
            val host =
                mount {
                    page(
                        leaderboard =
                            LeaderboardUiState.Data(
                                snapshot = LeaderboardSnapshot(listOf(entry("Ada")), emptyList(), emptyList()),
                                period = LeaderboardPeriod.Month,
                                category = LeaderboardCategory.Time,
                            ),
                    )
                }

            val on = host.querySelectorAll(".disc-chip.is-on")
            // Exactly one period and one category, never two of either.
            on.length shouldBe 2
            (on.item(0) as HTMLElement).textContent shouldBe "Month"
            (on.item(1) as HTMLElement).textContent shouldBe "Time"
            (on.item(0) as HTMLElement).getAttribute("aria-pressed") shouldBe "true"
        }

        test("pressing a period and a category reports each once, and not the other") {
            var period: LeaderboardPeriod? = null
            var category: LeaderboardCategory? = null
            val host =
                mount {
                    page(
                        leaderboard =
                            LeaderboardUiState.Data(
                                snapshot = LeaderboardSnapshot(listOf(entry("Ada")), emptyList(), emptyList()),
                                period = LeaderboardPeriod.Week,
                                category = LeaderboardCategory.Time,
                            ),
                        onSelectPeriod = { period = it },
                        onSelectCategory = { category = it },
                    )
                }

            val chips = host.querySelectorAll(".disc-chip")
            // Periods first (Week, Month, Year, All time), then the three categories.
            (chips.item(2) as HTMLElement).click()
            period shouldBe LeaderboardPeriod.Year
            category shouldBe null

            (chips.item(5) as HTMLElement).click()
            category shouldBe LeaderboardCategory.Books
        }

        test("the streak board ranks the longest run, through to the rendered row") {
            // The shared projection pins the rule; this pins that the page actually asks for the
            // streak list rather than rendering whichever list happens to be first.
            val host =
                mount {
                    page(
                        leaderboard =
                            LeaderboardUiState.Data(
                                snapshot =
                                    LeaderboardSnapshot(
                                        time = listOf(entry("WrongList", totalSeconds = 9_000)),
                                        books = listOf(entry("AlsoWrong", booksFinished = 5)),
                                        streak = listOf(entry("Ada", currentStreakDays = 3, longestStreakDays = 90)),
                                    ),
                                period = LeaderboardPeriod.Week,
                                category = LeaderboardCategory.Streak,
                            ),
                    )
                }

            host.querySelector(".disc-lb-name")!!.textContent shouldBe "Ada"
            host.querySelector(".disc-lb-stat")!!.textContent shouldBe "90 days"
        }

        test("an activity about a book opens it; one about nothing is not pressable") {
            // A control that looks tappable and does nothing is worse than plain text.
            var opened: String? = null
            val host =
                mount {
                    page(
                        activityState =
                            ActivityFeedUiState.Ready(listOf(activity("finished_book"), activity("user_joined", bookId = null))),
                        onOpenBook = { opened = it },
                    )
                }

            val rows = host.querySelectorAll(".disc-feed-row")
            rows.length shouldBe 2
            (rows.item(0) as HTMLElement).tagName shouldBe "BUTTON"
            (rows.item(1) as HTMLElement).tagName shouldBe "DIV"

            (rows.item(0) as HTMLElement).click()
            opened shouldBe "b1"
        }

        test("opening a book from a discovery card reports that book") {
            var opened: String? = null
            val host =
                mount {
                    page(
                        books =
                            DiscoverBooksUiState.Ready(
                                listOf(DiscoverUiBook("b7", "Dune", "Frank Herbert", null, null, null)),
                            ),
                        onOpenBook = { opened = it },
                    )
                }

            (host.querySelector(".disc-card") as HTMLElement).click()

            opened shouldBe "b7"
        }

        test("an empty section says so rather than rendering an empty frame") {
            val host =
                mount {
                    page(
                        recentlyAdded = RecentlyAddedUiState.Ready(emptyList()),
                        books =
                            DiscoverBooksUiState.Ready(
                                listOf(DiscoverUiBook("b1", "Dune", null, null, null, null)),
                            ),
                    )
                }

            host.textContent.orEmpty() shouldContain "Nothing new yet"
            // The populated section beside it is untouched.
            host.querySelectorAll(".disc-card").length shouldBe 1
        }

        test("recently added renders its own books, not the discovery ones") {
            val host =
                mount {
                    page(
                        recentlyAdded =
                            RecentlyAddedUiState.Ready(
                                listOf(RecentlyAddedUiBook("b9", "Piranesi", "Susanna Clarke", null, null, 0L)),
                            ),
                    )
                }

            host.textContent.orEmpty() shouldContain "Piranesi"
        }
    })
