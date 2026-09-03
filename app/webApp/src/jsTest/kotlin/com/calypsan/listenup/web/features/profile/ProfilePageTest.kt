package com.calypsan.listenup.web.features.profile

import com.calypsan.listenup.client.domain.model.ProfileRecentBook
import com.calypsan.listenup.client.domain.model.ProfileShelfSummary
import com.calypsan.listenup.client.presentation.profile.UserProfileUiState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.browser.document
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement

private const val NINETY_TWO_HOURS_MS = 92L * 3_600_000

/** A shelf size unlike any other number in these specs, so a wrong field cannot read as right. */
private const val SHELF_BOOKS = 7

private val hosts = mutableListOf<HTMLElement>()

internal fun readyProfile(
    userId: String = "u1",
    isOwnProfile: Boolean = false,
    displayName: String = "Simon Hull",
    tagline: String? = null,
    totalListenTimeMs: Long = NINETY_TWO_HOURS_MS,
    booksFinished: Int = 12,
    currentStreak: Int = 4,
    longestStreak: Int = 19,
    recentBooks: List<ProfileRecentBook> = listOf(ProfileRecentBook("b1", "The Way of Kings", null)),
    publicShelves: List<ProfileShelfSummary> = listOf(ProfileShelfSummary("s1", "Comfort reads", SHELF_BOOKS)),
): UserProfileUiState.Ready =
    UserProfileUiState.Ready(
        userId = userId,
        isOwnProfile = isOwnProfile,
        displayName = displayName,
        avatarColor = "",
        tagline = tagline,
        totalListenTimeMs = totalListenTimeMs,
        booksFinished = booksFinished,
        currentStreak = currentStreak,
        longestStreak = longestStreak,
        recentBooks = recentBooks,
        publicShelves = publicShelves,
    )

private fun page(
    state: UserProfileUiState,
    onOpenBook: (String) -> Unit = {},
    onOpenShelf: (String) -> Unit = {},
    onRetry: () -> Unit = {},
): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    hosts += host
    renderComposable(root = host) {
        ProfilePage(state = state, onOpenBook = onOpenBook, onOpenShelf = onOpenShelf, onRetry = onRetry)
    }
    return host
}

/**
 * A listener's page.
 *
 * What these pin: the header states only what the profile actually carries, a streak of zero is
 * absent rather than printed as a fact about someone who has not listened lately, a brand-new
 * account says so instead of trailing off after the header, and the same page serves you and
 * someone else with only its wording changing — never its contents, which the shared ViewModel
 * already decided.
 */
class ProfilePageTest :
    FunSpec({

        afterSpec {
            hosts.forEach { it.remove() }
            hosts.clear()
        }

        test("the header names the listener") {
            val host = page(readyProfile(displayName = "Simon Hull"))

            (host.querySelector(".prof-name") as HTMLElement).textContent shouldBe "Simon Hull"
        }

        test("the avatar asks for this user's own picture") {
            val host = page(readyProfile(userId = "u7"))

            (host.querySelector(".uav-img") as HTMLImageElement).src shouldContain "/api/v1/avatars/u7"
        }

        test("a tagline nobody wrote is absent, not an empty line") {
            val without = page(readyProfile(tagline = null))
            val blank = page(readyProfile(tagline = "   "))
            val written = page(readyProfile(tagline = "Mostly epic fantasy."))

            without.querySelector(".prof-tagline") shouldBe null
            blank.querySelector(".prof-tagline") shouldBe null
            (written.querySelector(".prof-tagline") as HTMLElement).textContent shouldBe "Mostly epic fantasy."
        }

        test("the stats read the profile's own numbers") {
            val host = page(readyProfile(totalListenTimeMs = NINETY_TWO_HOURS_MS, booksFinished = 12))

            val stats = host.querySelectorAll(".prof-stat")
            (stats.item(0) as HTMLElement).textContent.orEmpty() shouldContain "92h"
            (stats.item(1) as HTMLElement).textContent.orEmpty() shouldContain "12"
            (stats.item(1) as HTMLElement).textContent.orEmpty() shouldContain "books finished"
        }

        test("one finished book reads as 'book finished', not 'books finished'") {
            val host = page(readyProfile(booksFinished = 1))

            host.textContent.orEmpty() shouldContain "book finished"
        }

        // "0m listened" is a statistic about someone who has not started.
        test("a listener who has not started says so rather than reporting zero") {
            val host = page(readyProfile(totalListenTimeMs = 0))

            host.textContent.orEmpty() shouldContain "None yet"
            host.textContent.orEmpty() shouldNotContain "0m"
        }

        // A streak of zero is not a streak.
        test("a zero streak is absent") {
            val host = page(readyProfile(currentStreak = 0, longestStreak = 0))

            host.textContent.orEmpty() shouldNotContain "streak"
            host.textContent.orEmpty() shouldNotContain "record"
        }

        test("a live streak and a record are both shown when both exist") {
            val host = page(readyProfile(currentStreak = 4, longestStreak = 19))

            host.textContent.orEmpty() shouldContain "day streak"
            host.textContent.orEmpty() shouldContain "day record"
        }

        test("a recent book opens that book") {
            val opened = mutableListOf<String>()
            val host =
                page(
                    readyProfile(recentBooks = listOf(ProfileRecentBook("b9", "Elantris", null))),
                    onOpenBook = { opened += it },
                )

            (host.querySelector(".prof-book") as HTMLElement).click()

            opened shouldBe listOf("b9")
        }

        test("a shelf names its size and opens that shelf") {
            val opened = mutableListOf<String>()
            val host =
                page(
                    readyProfile(publicShelves = listOf(ProfileShelfSummary("s4", "Comfort reads", 7))),
                    onOpenShelf = { opened += it },
                )

            (host.querySelector(".prof-shelf-c") as HTMLElement).textContent shouldBe "7 books"
            (host.querySelector(".prof-shelf") as HTMLElement).click()

            opened shouldBe listOf("s4")
        }

        test("a one-book shelf reads as '1 book'") {
            val host = page(readyProfile(publicShelves = listOf(ProfileShelfSummary("s1", "Just one", 1))))

            (host.querySelector(".prof-shelf-c") as HTMLElement).textContent shouldBe "1 book"
        }

        // A page that stops after the header looks like it failed to finish loading.
        test("a brand-new account says so rather than trailing off after the header") {
            val host = page(readyProfile(recentBooks = emptyList(), publicShelves = emptyList()))

            host.textContent.orEmpty() shouldContain "Nothing shared yet"
        }

        test("your own empty profile is told what would fill it") {
            val host =
                page(
                    readyProfile(isOwnProfile = true, recentBooks = emptyList(), publicShelves = emptyList()),
                )

            host.textContent.orEmpty() shouldContain "Nothing here yet"
            host.textContent.orEmpty() shouldContain "will show up here"
        }

        // Only the wording changes; what a viewer may see was already decided upstream.
        test("your own profile is addressed to you, and someone else's is not") {
            val own = page(readyProfile(isOwnProfile = true))
            val other = page(readyProfile(isOwnProfile = false))

            own.textContent.orEmpty() shouldContain "What you've been listening to"
            own.textContent.orEmpty() shouldContain "Your shelves"
            other.textContent.orEmpty() shouldContain "Recently listened"
            other.textContent.orEmpty() shouldNotContain "Your shelves"
        }

        // `EditProfileViewModel` exists; a web form over it does not.
        test("there is no edit control, because there is no form behind one") {
            val host = page(readyProfile(isOwnProfile = true))

            host.textContent.orEmpty() shouldNotContain "Edit"
        }

        test("a profile that cannot be shown explains itself and offers a retry that fires") {
            var retries = 0
            val host = page(UserProfileUiState.Error("No such listener."), onRetry = { retries++ })

            host.textContent.orEmpty() shouldContain "No such listener."
            (host.querySelector(".empty button") as HTMLElement).click()

            retries shouldBe 1
        }

        test("loading draws a skeleton rather than an empty profile") {
            val host = page(UserProfileUiState.Loading)

            host.querySelector(".prof-skel").shouldNotBeNull()
            host.querySelector(".prof-name") shouldBe null
        }
    })
