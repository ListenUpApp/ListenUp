package com.calypsan.listenup.web.features.ratings

import androidx.compose.runtime.collectAsState
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.domain.repository.BookRatingRepository
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsViewModel
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.RECOMPOSE_TIMEOUT_MS
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.awaitGone
import com.calypsan.listenup.web.awaitPresent
import com.calypsan.listenup.web.design.halfStarsAt
import com.calypsan.listenup.web.design.halfStarsForKey
import com.calypsan.listenup.web.features.library.BOOK_SORT_CATEGORIES
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.w3c.dom.EventInit
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event
import org.w3c.dom.events.EventTarget
import org.w3c.dom.events.KeyboardEvent
import org.w3c.dom.events.KeyboardEventInit

private fun rating(
    halfStars: Int,
    note: String? = null,
    userId: String = "me",
) = ListenerRating(bookId = "b1", userId = userId, halfStars = halfStars, note = note, ratedAtMs = 0L)

private fun ready(
    mine: ListenerRating? = null,
    listeners: ListenerAverage? = null,
) = BookRatingsUiState.Ready(listeners = listeners, mine = mine)

private fun button(
    host: HTMLElement,
    label: String,
): HTMLButtonElement? =
    host
        .querySelectorAll("button")
        .asList()
        .filterIsInstance<HTMLButtonElement>()
        .firstOrNull { it.textContent?.trim() == label }

private fun slider(host: HTMLElement): HTMLElement = host.querySelector("[role=slider]") as HTMLElement

private fun EventTarget.press(key: String) {
    dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)))
}

/** A repository over an in-memory list, recording the writes the ViewModel sends it. */
private class FakeBookRatingRepository : BookRatingRepository {
    val ratings = MutableStateFlow<List<ListenerRating>>(emptyList())
    val rated = mutableListOf<Triple<String, Int, String?>>()
    val cleared = mutableListOf<String>()

    override fun observeForBook(bookId: String): Flow<List<ListenerRating>> = ratings.map { all -> all.filter { it.bookId == bookId } }

    override fun observeAverages(): Flow<Map<String, ListenerAverage>> = flowOf(emptyMap())

    override suspend fun rate(
        bookId: String,
        halfStars: Int,
        note: String?,
    ): AppResult<Unit> {
        rated += Triple(bookId, halfStars, note)
        ratings.value = ratings.value.filterNot { it.userId == "me" } +
            ListenerRating(bookId, "me", halfStars, note, ratedAtMs = 0L)
        return AppResult.Success(Unit)
    }

    override suspend fun clear(bookId: String): AppResult<Unit> {
        cleared += bookId
        ratings.value = ratings.value.filterNot { it.userId == "me" }
        return AppResult.Success(Unit)
    }
}

/**
 * The rating surfaces on Book Detail — the panel, the rate dialog and its stars.
 *
 * ⛔ The last specs matter most. Web has shipped sessions that rendered a ViewModel's state and
 * never wired a single one of its actions, so these drive a click all the way through
 * [graphBookRatings] into a real [BookRatingsViewModel] and assert the repository heard it.
 */
class RatingsTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun panel(
            state: BookRatingsUiState,
            onRate: (Int, String?) -> Unit = { _, _ -> },
            onClear: () -> Unit = {},
        ): HTMLElement = mounts.mount { RatingsPanel(state = state, onRate = onRate, onClear = onClear) }

        test("the panel draws nothing while the ratings are loading") {
            panel(BookRatingsUiState.Loading).textContent?.trim() shouldBe ""
        }

        test("an unrated book invites a rating, with no average when nobody has rated it") {
            val host = panel(ready())

            button(host, "Rate").shouldNotBeNull()
            host.querySelector(".rt-avg").shouldBeNull()
            host.querySelector(".rt-mine").shouldBeNull()
        }

        test("a rated book shows your stars and offers Edit, under your listeners' average") {
            val host =
                panel(ready(mine = rating(7), listeners = ListenerAverage(averageHalfStars = 8.0, count = 3)))

            button(host, "Rate").shouldBeNull()
            button(host, "Edit").shouldNotBeNull()
            (host.querySelector(".rt-mine-l") as HTMLElement).textContent shouldBe "Your rating"
            (host.querySelector(".rt-mine [role=img]") as HTMLElement).getAttribute("aria-label") shouldBe
                "3.5 out of 5 stars"
            // ⛔ "4", never "4.0": starsLabel is the one formatter, and a JS double would print "4".
            (host.querySelector(".rt-avg [aria-hidden=true]") as HTMLElement).textContent shouldBe
                "Your listeners ★ 4 (3)"
            (host.querySelector(".rt-avg .rt-sr") as HTMLElement).textContent shouldBe
                "Your listeners: 4 out of 5 stars, from 3 ratings"
        }

        test("one listener's rating is read as one rating, in the words Android and iOS use") {
            val host = panel(ready(mine = null, listeners = ListenerAverage(averageHalfStars = 8.0, count = 1)))

            (host.querySelector(".rt-avg .rt-sr") as HTMLElement).textContent shouldBe
                "Your listeners: 4 out of 5 stars, from 1 rating"
        }

        test("the stars step one half per arrow key, and Home and End jump to one and five") {
            val host = panel(ready(mine = rating(6)))
            button(host, "Edit")!!.click()
            val stars = awaitPresent(host, "[role=slider]")

            stars.getAttribute("aria-valuenow") shouldBe "3"
            stars.getAttribute("aria-valuetext") shouldBe "3 out of 5 stars"
            stars.getAttribute("aria-label") shouldBe "Rating"
            stars.getAttribute("tabindex") shouldBe "0"

            stars.press("ArrowRight")
            awaitFrame()
            slider(host).getAttribute("aria-valuenow") shouldBe "3.5"

            slider(host).press("ArrowLeft")
            awaitFrame()
            slider(host).press("ArrowDown")
            awaitFrame()
            slider(host).getAttribute("aria-valuenow") shouldBe "2.5"

            slider(host).press("End")
            awaitFrame()
            slider(host).getAttribute("aria-valuenow") shouldBe "5"
            slider(host).press("ArrowUp")
            awaitFrame()
            slider(host).getAttribute("aria-valuenow") shouldBe "5"

            slider(host).press("Home")
            awaitFrame()
            slider(host).getAttribute("aria-valuetext") shouldBe "1 out of 5 stars"
        }

        test("an unrated slider says so, and its first arrow lands on one star") {
            val host = panel(ready())
            button(host, "Rate")!!.click()
            val stars = awaitPresent(host, "[role=slider]")

            stars.getAttribute("aria-valuenow").shouldBeNull()
            stars.getAttribute("aria-valuetext") shouldBe "Not rated"

            stars.press("ArrowRight")
            awaitFrame()
            slider(host).getAttribute("aria-valuenow") shouldBe "1"
        }

        test("the key and click arithmetic stays inside one to five stars") {
            halfStarsForKey("ArrowRight", 0) shouldBe 2
            halfStarsForKey("ArrowLeft", 2) shouldBe 2
            halfStarsForKey("ArrowRight", 10) shouldBe 10
            halfStarsForKey("Tab", 6).shouldBeNull()
            // Five 20px stars: the left half of the third star is 2.5, its right half 3.
            halfStarsAt(45.0, 100.0) shouldBe 5
            halfStarsAt(55.0, 100.0) shouldBe 6
            halfStarsAt(0.0, 100.0) shouldBe 2
            halfStarsAt(100.0, 100.0) shouldBe 10
        }

        test("Save is disabled until a star is chosen, then saves the rating and the trimmed note") {
            val saved = mutableListOf<Pair<Int, String?>>()
            val host = panel(ready(), onRate = { stars, note -> saved += stars to note })
            button(host, "Rate")!!.click()
            awaitPresent(host, "[role=slider]")

            button(host, "Save")!!.hasAttribute("disabled") shouldBe true
            button(host, "Remove rating").shouldBeNull()

            slider(host).press("End")
            awaitFrame()
            val note = host.querySelector("textarea") as HTMLTextAreaElement
            note.getAttribute("maxlength") shouldBe "280"
            note.value = "  Loved it.  "
            note.dispatchEvent(
                Event("input", EventInit(bubbles = true)),
            )
            awaitFrame()
            (host.querySelector(".rt-count") as HTMLElement).textContent shouldBe "13/280"

            button(host, "Save")!!.hasAttribute("disabled") shouldBe false
            button(host, "Save")!!.click()
            awaitGone(host, "dialog")

            saved shouldContainExactly listOf(10 to "Loved it.")
        }

        test("the dialog opens on your rating and note, and Remove rating clears it") {
            var cleared = 0
            val host = panel(ready(mine = rating(9, note = "Great")), onClear = { cleared++ })
            button(host, "Edit")!!.click()
            awaitPresent(host, "[role=slider]")

            slider(host).getAttribute("aria-valuenow") shouldBe "4.5"
            (host.querySelector("textarea") as HTMLTextAreaElement).value shouldBe "Great"
            (host.querySelector(".rt-count") as HTMLElement).textContent shouldBe "5/280"

            button(host, "Remove rating")!!.click()
            awaitGone(host, "dialog")

            cleared shouldBe 1
        }

        test("Save reaches the real ViewModel's rate, and the panel shows the new rating") {
            val repository = FakeBookRatingRepository()
            val koin =
                koinApplication {
                    modules(
                        module {
                            factory { params ->
                                BookRatingsViewModel(params.get(), repository, flowOf("me"), ErrorBus())
                            }
                        },
                    )
                }.koin
            val session = graphBookRatings(koin)("b1")
            val host =
                mounts.mount {
                    RatingsPanel(
                        state = session.state.collectAsState().value,
                        onRate = session.rate,
                        onClear = session.clear,
                    )
                }
            try {
                awaitPresent(host, ".rt-rate").click()
                awaitPresent(host, "[role=slider]").press("ArrowRight")
                awaitFrame()
                slider(host).press("ArrowRight")
                awaitFrame()
                button(host, "Save")!!.click()

                awaitPresent(host, ".rt-mine")
                repository.rated shouldContainExactly listOf(Triple("b1", 3, null))

                button(host, "Edit")!!.click()
                awaitPresent(host, "[role=slider]")
                button(host, "Remove rating")!!.click()

                awaitPresent(host, ".rt-rate")
                withTimeout(RECOMPOSE_TIMEOUT_MS) { while (repository.cleared.isEmpty()) delay(10) }
                repository.cleared shouldContainExactly listOf("b1")
            } finally {
                session.close()
            }
        }

        test("the library sorts books by your listeners' rating") {
            BOOK_SORT_CATEGORIES shouldContain SortCategory.LISTENER_RATING
            BOOK_SORT_CATEGORIES.indexOf(SortCategory.LISTENER_RATING) shouldBe
                BOOK_SORT_CATEGORIES.indexOf(SortCategory.ADDED) + 1
        }
    })
