package com.calypsan.listenup.web.features.ratings

import androidx.compose.runtime.collectAsState
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.ExternalRating
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.domain.model.ScoreSource
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.BookRatingRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsViewModel
import com.calypsan.listenup.client.presentation.library.SortCategory
import com.calypsan.listenup.core.error.ErrorBus
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.RECOMPOSE_TIMEOUT_MS
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.awaitGone
import com.calypsan.listenup.web.awaitPresent
import com.calypsan.listenup.web.design.RatingStars
import com.calypsan.listenup.web.design.halfStarsAt
import com.calypsan.listenup.web.design.halfStarsForKey
import com.calypsan.listenup.web.features.library.BOOK_SORT_CATEGORIES
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainString
import kotlinx.coroutines.CompletableDeferred
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
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.events.MouseEventInit

private fun rating(
    halfStars: Int,
    note: String? = null,
    userId: String = "me",
) = ListenerRating(bookId = "b1", userId = userId, halfStars = halfStars, note = note, ratedAtMs = 0L)

private fun ready(
    mine: ListenerRating? = null,
    listeners: ListenerAverage? = null,
    external: CombinedScore? = null,
    breakdown: List<ExternalRating> = emptyList(),
    canRefresh: Boolean = false,
    isRefreshingExternal: Boolean = false,
) = BookRatingsUiState.Ready(
    listeners = listeners,
    mine = mine,
    external = external,
    breakdown = breakdown,
    canRefresh = canRefresh,
    isRefreshingExternal = isRefreshingExternal,
)

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

/** Clicks [element] [fraction] of the way across it from its left edge. */
private fun clickAcross(
    element: HTMLElement,
    fraction: Double,
) {
    val rect = element.getBoundingClientRect()
    element.dispatchEvent(
        MouseEvent(
            "click",
            MouseEventInit(
                clientX = (rect.left + rect.width * fraction).toInt(),
                clientY = (rect.top + rect.height / 2).toInt(),
                bubbles = true,
                cancelable = true,
            ),
        ),
    )
}

private fun EventTarget.press(key: String) {
    dispatchEvent(KeyboardEvent("keydown", KeyboardEventInit(key = key, bubbles = true, cancelable = true)))
}

/**
 * A repository over an in-memory list, recording the writes the ViewModel sends it.
 *
 * [refreshGate] is already-completed by default, so [refreshExternal] returns at once; a test of
 * the in-flight window passes its own incomplete [CompletableDeferred] and completes it itself.
 */
private class FakeBookRatingRepository(
    private val refreshGate: CompletableDeferred<Unit> = CompletableDeferred(Unit),
) : BookRatingRepository {
    val ratings = MutableStateFlow<List<ListenerRating>>(emptyList())
    val rated = mutableListOf<Triple<String, Int, String?>>()
    val cleared = mutableListOf<String>()
    val refreshed = mutableListOf<String>()
    var external = MutableStateFlow<List<ExternalRating>>(emptyList())

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

    override fun observeExternalForBook(bookId: String): Flow<List<ExternalRating>> = external

    override fun observeCombinedScores(): Flow<Map<String, CombinedScore>> = flowOf(emptyMap())

    override fun observeCombinedScore(bookId: String): Flow<CombinedScore?> =
        external.map { rows -> rows.takeIf { it.isNotEmpty() }?.let { CombinedScore(average = 4.4, count = it.sumOf { r -> r.count }) } }

    override suspend fun refreshExternal(bookId: String): AppResult<Unit> {
        refreshed += bookId
        refreshGate.await()
        return AppResult.Success(Unit)
    }
}

/** [BookRatingsViewModel] only reads [observeIsAdmin] here — [isAdmin] governs `canRefresh`. */
private class FakeUserRepository(
    private val isAdmin: Boolean = false,
) : UserRepository {
    override fun observeCurrentUser(): Flow<User?> = flowOf(null)

    override fun observeIsAdmin(): Flow<Boolean> = flowOf(isAdmin)

    override suspend fun getCurrentUser(): User? = null

    override suspend fun saveUser(user: User) = Unit

    override suspend fun clearUsers() = Unit

    override suspend fun refreshCurrentUser(): User? = null
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
            onRefreshExternal: () -> Unit = {},
        ): HTMLElement =
            mounts.mount {
                RatingsPanel(state = state, onRate = onRate, onClear = onClear, onRefreshExternal = onRefreshExternal)
            }

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

        test("a click counts stars from the start edge, which is the right in a right-to-left page") {
            var chosen: Int? = null
            val ltr = mounts.mount { RatingStars(halfStars = 0, onHalfStarsChange = { chosen = it }) }
            clickAcross(slider(ltr), 0.75)
            chosen shouldBe 8

            chosen = null
            val rtl = mounts.mount { RatingStars(halfStars = 0, onHalfStarsChange = { chosen = it }) }
            rtl.dir = "rtl"
            // Three quarters from the left is a quarter from the start: the left half of star two.
            clickAcross(slider(rtl), 0.75)
            chosen shouldBe 3
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
                                BookRatingsViewModel(params.get(), repository, flowOf("me"), ErrorBus(), FakeUserRepository())
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

        test("the outside score stays hidden until an enabled source has rated the book") {
            val host = panel(ready())

            host.querySelector(".rt-external").shouldBeNull()
        }

        test("an admin can refresh ratings before any score exists") {
            var refreshed = false
            val host = panel(ready(canRefresh = true), onRefreshExternal = { refreshed = true })

            val action = host.querySelector(".rt-refresh-first") as HTMLElement
            action.textContent shouldBe "Refresh ratings"
            action.dispatchEvent(MouseEvent("click", MouseEventInit(bubbles = true, cancelable = true)))

            refreshed shouldBe true
        }

        test("the inline refresh action is busy while a refresh is in flight") {
            val host = panel(ready(canRefresh = true, isRefreshingExternal = true))

            val action = host.querySelector(".rt-refresh-first") as HTMLElement
            action.textContent shouldBe "Refreshing…"
            action.hasAttribute("disabled") shouldBe true
        }

        test("a non-admin sees no refresh action before any score exists") {
            val host = panel(ready(canRefresh = false))

            host.querySelector(".rt-refresh-first").shouldBeNull()
        }

        test("no inline refresh action once a score exists") {
            val host = panel(ready(external = CombinedScore(average = 4.4, count = 12_000), canRefresh = true))

            host.querySelector(".rt-refresh-first").shouldBeNull()
            host.querySelector(".rt-external").shouldNotBeNull()
        }

        test("the outside headline shows one decimal, never a dropped trailing zero") {
            val host = panel(ready(external = CombinedScore(average = 4.0, count = 12_000)))

            val headline = host.querySelector(".rt-external") as HTMLElement
            // ⛔ "4.0", never "4": a JS double drops the trailing zero, and starsLabel would round
            // a continuous average to the nearest half star ("4.5") instead of printing it exactly.
            headline.getAttribute("aria-label") shouldBe "Rated 4.0 out of 5 stars from 12k ratings"
            headline.textContent shouldBe "★ 4.0 · 12k ratings"
        }

        test("the headline rounds half up, never to the nearest half star") {
            val host = panel(ready(external = CombinedScore(average = 4.45, count = 900)))

            val headline = host.querySelector(".rt-external") as HTMLElement
            // ⛔ 4.45 reads "4.5" — round half up on the true average. `starsLabel(average * 2)`
            // would instead round to the nearest half star and print the same "4.5" only by
            // coincidence here; the regression it guards against is 4.4 printing "4.5".
            headline.getAttribute("aria-label") shouldBe "Rated 4.5 out of 5 stars from 900 ratings"
            headline.textContent shouldBe "★ 4.5 · 900 ratings"
        }

        test("one outside rating reads as one rating, in the words the listener average uses") {
            val host = panel(ready(external = CombinedScore(average = 4.4, count = 1)))

            val headline = host.querySelector(".rt-external") as HTMLElement
            headline.getAttribute("aria-label") shouldBe "Rated 4.4 out of 5 stars from 1 rating"
            headline.textContent shouldBe "★ 4.4 · 1 rating"
        }

        test("tapping the headline opens a breakdown sheet listing one row per source") {
            val host =
                panel(
                    ready(
                        external = CombinedScore(average = 4.4, count = 8_400),
                        breakdown =
                            listOf(
                                ExternalRating(source = ExternalRatingSource.AUDIBLE, average = 4.5, count = 8_100),
                                ExternalRating(source = ExternalRatingSource.GOODREADS, average = 4.0, count = 300),
                            ),
                    ),
                )

            (host.querySelector(".rt-external") as HTMLElement).click()
            val dialog = awaitPresent(host, "dialog")

            dialog.textContent.orEmpty() shouldContainString "Ratings"
            val rows = dialog.querySelectorAll(".rt-source-row").asList().map { it.textContent }
            rows shouldContainExactly listOf("Audible · 4.5 · 8.1k", "Goodreads · 4.0 · 300")
        }

        test("the refresh button offers itself only to an admin") {
            val host =
                panel(
                    ready(
                        external = CombinedScore(average = 4.4, count = 100),
                        breakdown = listOf(ExternalRating(source = ExternalRatingSource.AUDIBLE, average = 4.4, count = 100)),
                        canRefresh = false,
                    ),
                )

            (host.querySelector(".rt-external") as HTMLElement).click()
            awaitPresent(host, "dialog")

            host.querySelector(".rt-refresh").shouldBeNull()
        }

        test("the refresh button is busy while isRefreshingExternal is true, and plain when it is not") {
            val breakdown = listOf(ExternalRating(source = ExternalRatingSource.AUDIBLE, average = 4.4, count = 100))
            val idle =
                panel(
                    ready(
                        external = CombinedScore(average = 4.4, count = 100),
                        breakdown = breakdown,
                        canRefresh = true,
                        isRefreshingExternal = false,
                    ),
                )
            (idle.querySelector(".rt-external") as HTMLElement).click()
            awaitPresent(idle, "dialog")
            (idle.querySelector(".rt-refresh") as HTMLElement).hasAttribute("disabled") shouldBe false
            (idle.querySelector(".rt-refresh") as HTMLElement).textContent shouldBe "Refresh ratings"

            val busy =
                panel(
                    ready(
                        external = CombinedScore(average = 4.4, count = 100),
                        breakdown = breakdown,
                        canRefresh = true,
                        isRefreshingExternal = true,
                    ),
                )
            (busy.querySelector(".rt-external") as HTMLElement).click()
            awaitPresent(busy, "dialog")
            (busy.querySelector(".rt-refresh") as HTMLElement).hasAttribute("disabled") shouldBe true
            (busy.querySelector(".rt-refresh") as HTMLElement).textContent shouldBe "Refreshing…"
        }

        test("refresh reaches the real ViewModel's refreshExternal, admin-gated end to end, and stays busy until it answers") {
            val refreshGate = CompletableDeferred<Unit>()
            val repository =
                FakeBookRatingRepository(refreshGate = refreshGate).apply {
                    external.value = listOf(ExternalRating(source = ExternalRatingSource.AUDIBLE, average = 4.4, count = 100))
                }
            val koin =
                koinApplication {
                    modules(
                        module {
                            factory { params ->
                                BookRatingsViewModel(
                                    params.get(),
                                    repository,
                                    flowOf("me"),
                                    ErrorBus(),
                                    FakeUserRepository(isAdmin = true),
                                )
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
                        onRefreshExternal = session.refreshExternal,
                    )
                }
            try {
                awaitPresent(host, ".rt-external").click()
                awaitPresent(host, ".rt-refresh").click()

                withTimeout(RECOMPOSE_TIMEOUT_MS) { while (repository.refreshed.isEmpty()) delay(10) }
                repository.refreshed shouldContainExactly listOf("b1")

                // ⛔ The RPC hasn't answered yet (the gate is still open) — the button must say so.
                withTimeout(RECOMPOSE_TIMEOUT_MS) {
                    while (button(host, "Refreshing…")?.hasAttribute("disabled") != true) delay(10)
                }

                refreshGate.complete(Unit)

                withTimeout(RECOMPOSE_TIMEOUT_MS) {
                    while (button(host, "Refresh ratings")?.hasAttribute("disabled") != false) delay(10)
                }
            } finally {
                session.close()
            }
        }

        // --- What each source contributes to the ListenUp score: the same rows as Android ---

        val audible = ExternalRating(source = ExternalRatingSource.AUDIBLE, average = 4.7, count = 1_007)
        val goodreads = ExternalRating(source = ExternalRatingSource.GOODREADS, average = 4.2, count = 100_000)

        suspend fun breakdownRows(state: BookRatingsUiState): Pair<String, List<String>> {
            val host = panel(state)
            (host.querySelector(".rt-external") as HTMLElement).click()
            val dialog = awaitPresent(host, "dialog")
            return dialog.textContent.orEmpty() to
                dialog.querySelectorAll(".rt-source-row").asList().map { it.textContent.orEmpty() }
        }

        test("each outside row shows its share of the score, to a whole percent") {
            val (_, rows) =
                breakdownRows(
                    ready(
                        external =
                            CombinedScore(
                                average = 4.3,
                                count = 101_007,
                                shares =
                                    mapOf(
                                        ScoreSource.Outside(ExternalRatingSource.GOODREADS) to 0.6249,
                                        ScoreSource.Outside(ExternalRatingSource.AUDIBLE) to 0.3751,
                                    ),
                            ),
                        breakdown = listOf(goodreads, audible),
                    ),
                )

            rows shouldContainExactly listOf("Goodreads · 4.2 · 100k · 62%", "Audible · 4.7 · 1k · 38%")
        }

        test("a score from several sources says how many") {
            val (text, _) =
                breakdownRows(
                    ready(
                        external =
                            CombinedScore(
                                average = 4.3,
                                count = 101_007,
                                shares =
                                    mapOf(
                                        ScoreSource.Outside(ExternalRatingSource.GOODREADS) to 0.6,
                                        ScoreSource.Outside(ExternalRatingSource.AUDIBLE) to 0.4,
                                    ),
                            ),
                        breakdown = listOf(goodreads, audible),
                    ),
                )

            text shouldContainString "Combined from 2 sources"
        }

        test("a score from one source does not say combined") {
            val (text, rows) =
                breakdownRows(
                    ready(
                        external =
                            CombinedScore(
                                average = 4.4,
                                count = 1_007,
                                shares = mapOf(ScoreSource.Outside(ExternalRatingSource.AUDIBLE) to 1.0),
                            ),
                        breakdown = listOf(audible),
                    ),
                )

            text.contains("Combined from") shouldBe false
            rows shouldContainExactly listOf("Audible · 4.7 · 1k · 100%")
        }

        test("your listeners get their own row when they are part of the score") {
            val (text, rows) =
                breakdownRows(
                    ready(
                        external =
                            CombinedScore(
                                average = 4.4,
                                count = 1_010,
                                shares =
                                    mapOf(
                                        ScoreSource.Outside(ExternalRatingSource.AUDIBLE) to 0.8,
                                        ScoreSource.Listeners to 0.2,
                                    ),
                            ),
                        listeners = ListenerAverage(averageHalfStars = 8.0, count = 3),
                        breakdown = listOf(audible),
                    ),
                )

            text shouldContainString "Combined from 2 sources"
            rows shouldContainExactly listOf("Audible · 4.7 · 1k · 80%", "Your listeners · 4.0 · 3 · 20%")
        }

        test("no listeners row when no listener has rated the book") {
            val (_, rows) =
                breakdownRows(
                    ready(
                        external =
                            CombinedScore(
                                average = 4.4,
                                count = 1_007,
                                shares = mapOf(ScoreSource.Outside(ExternalRatingSource.AUDIBLE) to 1.0),
                            ),
                        listeners = null,
                        breakdown = listOf(audible),
                    ),
                )

            rows.any { it.startsWith("Your listeners") } shouldBe false
        }

        // --- A book only your listeners have rated ---

        val listenersOnly =
            ready(
                listeners = ListenerAverage(averageHalfStars = 9.0, count = 3),
                // The score reads 4.5 off the listeners' own curve onto ListenUp's: a different
                // number from the same three ratings.
                external = CombinedScore(average = 4.3, count = 3, shares = mapOf(ScoreSource.Listeners to 1.0)),
            )

        test("a book only your listeners rated shows their average once, not a second recalibrated number") {
            val host = panel(listenersOnly)

            (host.querySelector(".rt-avg .rt-sr") as HTMLElement).textContent shouldBe
                "Your listeners: 4.5 out of 5 stars, from 3 ratings"
            host.querySelector(".rt-external").shouldBeNull()
        }

        test("an admin can still refresh a book only your listeners rated") {
            val host = panel(listenersOnly.copy(canRefresh = true))

            (host.querySelector(".rt-refresh-first") as HTMLElement).textContent shouldBe "Refresh ratings"
        }

        test("once an outside source joins your listeners the headline returns beside their line") {
            val host =
                panel(
                    listenersOnly.copy(
                        external =
                            CombinedScore(
                                average = 4.4,
                                count = 1_010,
                                shares =
                                    mapOf(
                                        ScoreSource.Outside(ExternalRatingSource.AUDIBLE) to 0.8,
                                        ScoreSource.Listeners to 0.2,
                                    ),
                            ),
                        breakdown = listOf(audible),
                    ),
                )

            (host.querySelector(".rt-external") as HTMLElement).getAttribute("aria-label") shouldBe
                "Rated 4.4 out of 5 stars from 1k ratings"
            host.querySelector(".rt-avg").shouldNotBeNull()
        }

        test("the library sorts books by the outside world's rating and by your listeners'") {
            BOOK_SORT_CATEGORIES shouldContain SortCategory.RATING
            BOOK_SORT_CATEGORIES shouldContain SortCategory.LISTENER_RATING
            BOOK_SORT_CATEGORIES.indexOf(SortCategory.LISTENER_RATING) shouldBe
                BOOK_SORT_CATEGORIES.indexOf(SortCategory.ADDED) + 2
        }
    })
