package com.calypsan.listenup.web.features.ratings

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsEvent
import com.calypsan.listenup.web.design.ToastAction
import com.calypsan.listenup.web.design.WebAppSurface
import com.calypsan.listenup.web.mountAt
import io.kotest.matchers.shouldNotBe
import kotlinx.browser.document
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
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
import org.w3c.dom.pointerevents.PointerEvent
import org.w3c.dom.pointerevents.PointerEventInit

private fun rating(
    halfStars: Int,
    note: String? = null,
    userId: String = "me",
    fromHardcover: Boolean = false,
) = ListenerRating(bookId = "b1", userId = userId, halfStars = halfStars, note = note, ratedAtMs = 0L, fromHardcover = fromHardcover)

private fun ready(
    mine: ListenerRating? = null,
    listeners: ListenerAverage? = null,
    external: CombinedScore? = null,
    breakdown: List<ExternalRating> = emptyList(),
    canRefresh: Boolean = false,
    isRefreshingExternal: Boolean = false,
    isCheckingExternal: Boolean = false,
) = BookRatingsUiState.Ready(
    listeners = listeners,
    mine = mine,
    external = external,
    breakdown = breakdown,
    canRefresh = canRefresh,
    isRefreshingExternal = isRefreshingExternal,
    isCheckingExternal = isCheckingExternal,
)

private const val DAY = 86_400_000L
private val AUDIBLE =
    ExternalRating(source = ExternalRatingSource.AUDIBLE, average = 4.8, count = 11_000, fetchedAtMs = 1_000L * DAY - 3 * DAY)
private val HARDCOVER = ExternalRating(source = ExternalRatingSource.HARDCOVER, average = 4.5, count = 1_200)
private val SCORE =
    CombinedScore(
        average = 4.6,
        count = 12_203,
        shares =
            mapOf(
                ScoreSource.Outside(ExternalRatingSource.AUDIBLE) to 0.62,
                ScoreSource.Outside(ExternalRatingSource.HARDCOVER) to 0.30,
                ScoreSource.Listeners to 0.08,
            ),
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

/** Dispatches a pointer event of [type] [fraction] of the way across [element] from its left edge. */
private fun EventTarget.pointer(
    type: String,
    element: HTMLElement,
    fraction: Double,
) {
    val rect = element.getBoundingClientRect()
    dispatchEvent(
        PointerEvent(
            type,
            PointerEventInit(
                pointerId = 1,
                clientX = (rect.left + rect.width * fraction).toInt(),
                clientY = (rect.top + rect.height / 2).toInt(),
                bubbles = true,
                cancelable = true,
            ),
        ),
    )
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

    override fun observeExternalCheck(bookId: String): Flow<Boolean> = flowOf()
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
 * The rating surfaces on Book Detail — the panel, its sources dialog, the note dialog and the stars.
 *
 * ⛔ The ViewModel specs matter most. Web has shipped sessions that rendered a ViewModel's state and
 * never wired a single one of its actions, so these drive a click all the way through
 * [graphBookRatings] into a real [BookRatingsViewModel] and assert the repository heard it.
 */
class RatingsTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun panel(
            state: BookRatingsUiState,
            onSetStars: (Int) -> Unit = {},
            onRate: (Int, String?) -> Unit = { _, _ -> },
            onClear: () -> Unit = {},
            onRefreshExternal: () -> Unit = {},
            nowMs: Long = 0L,
        ): HTMLElement =
            mounts.mount {
                RatingsPanel(
                    state = state,
                    onSetStars = onSetStars,
                    onRate = onRate,
                    onClear = onClear,
                    onRefreshExternal = onRefreshExternal,
                    nowMs = nowMs,
                )
            }

        /** A slider over its own state, so key steps genuinely move it. */
        fun stars(initial: Int): HTMLElement =
            mounts.mount {
                var halfStars by remember { mutableIntStateOf(initial) }
                RatingStars(halfStars = halfStars, onHalfStarsChange = { halfStars = it })
            }

        suspend fun openSources(host: HTMLElement): HTMLElement {
            (host.querySelector("button.rt-score") as HTMLButtonElement).click()
            return awaitPresent(document.body!!, "dialog .rt-srcs")
                .closest("dialog") as HTMLElement
        }

        fun spokenRows(dialog: HTMLElement): List<String> =
            dialog.querySelectorAll(".rt-src .rt-sr").asList().map { it.textContent.orEmpty() }

        // --- The panel: yours first ---

        test("the panel draws nothing while the ratings are loading") {
            panel(BookRatingsUiState.Loading).textContent?.trim() shouldBe ""
        }

        test("the panel is headed Ratings and invites a first rating") {
            val host = panel(ready())
            awaitFrame()

            host.textContent!!.shouldContainString("Ratings")
            host.textContent!!.shouldContainString("Your rating")
            host.textContent!!.shouldContainString("Not rated")
            host.textContent!!.shouldContainString("Tap a star. Drag for half stars.")
            button(host, "Remove").shouldBeNull()
        }

        test("your rating from Hardcover says so; one set here does not") {
            val imported = panel(ready(mine = rating(9, fromHardcover = true)))
            awaitFrame()
            imported.textContent!!.shouldContainString("Rated on Hardcover")

            val yours = panel(ready(mine = rating(9)))
            awaitFrame()
            (yours.textContent ?: "").contains("Rated on Hardcover") shouldBe false
        }

        test("the panel's stars are a slider named Your rating, saying the value it shows") {
            val host = panel(ready(mine = rating(9)))
            awaitFrame()

            val stars = slider(host)
            stars.getAttribute("aria-label") shouldBe "Your rating"
            stars.getAttribute("aria-valuetext") shouldBe "4.5 out of 5 stars"
            (host.querySelector(".rt-you-v") as HTMLElement).textContent shouldBe "4.5"
        }

        test("a click on the stars saves at once") {
            val saved = mutableListOf<Int>()
            val host = panel(ready(), onSetStars = { saved += it })
            awaitFrame()

            clickAcross(slider(host), 0.75)

            saved shouldContainExactly listOf(8)
        }

        test("a key step on the panel's stars saves each step") {
            val saved = mutableListOf<Int>()
            val host = panel(ready(mine = rating(6)), onSetStars = { saved += it })
            awaitFrame()

            slider(host).press("ArrowRight")

            saved shouldContainExactly listOf(7)
        }

        test("a drag shows each half it crosses before anything saves") {
            val saved = mutableListOf<Int>()
            val host = panel(ready(), onSetStars = { saved += it })
            awaitFrame()
            val stars = slider(host)

            stars.pointer("pointerdown", stars, 0.15)
            stars.pointer("pointermove", stars, 0.55)
            awaitFrame()

            saved shouldBe emptyList()
            slider(host).getAttribute("aria-valuetext") shouldBe "3 out of 5 stars"
            (host.querySelector(".rt-you-v") as HTMLElement).textContent shouldBe "3"
        }

        test("your note shows quoted, with Edit note and Remove") {
            var cleared = 0
            val host = panel(ready(mine = rating(9, note = "Loved it")), onClear = { cleared++ })
            awaitFrame()

            host.textContent!!.shouldContainString("“Loved it”")
            button(host, "Edit note").shouldNotBeNull()
            button(host, "Remove").shouldNotBeNull()!!.click()

            cleared shouldBe 1
        }

        test("Remove hands focus to the stars, so it is not lost with the button") {
            val host = panel(ready(mine = rating(8)))
            awaitFrame()

            val remove = button(host, "Remove")!!
            remove.focus()
            remove.click()

            document.activeElement shouldBe slider(host)
        }

        test("a request to focus the stars lands on them") {
            var request by mutableIntStateOf(0)
            val host =
                mounts.mount {
                    RatingsPanel(
                        state = ready(mine = rating(8)),
                        onSetStars = {},
                        onRate = { _, _ -> },
                        onClear = {},
                        starsFocusRequest = request,
                    )
                }
            awaitFrame()
            document.activeElement shouldNotBe slider(host)

            request++
            awaitFrame()

            document.activeElement shouldBe slider(host)
        }

        test("a rating without a note offers Add a note, which opens the note on your rating") {
            val host = panel(ready(mine = rating(8)))
            awaitFrame()

            button(host, "Add a note").shouldNotBeNull()!!.click()
            val dialog = awaitPresent(host, "dialog")

            dialog.querySelector("[role=slider]")!!.getAttribute("aria-valuenow") shouldBe "4"
        }

        test("the keyboard hint is there for the slider's focus to reveal") {
            val host = panel(ready())
            awaitFrame()

            val keys = host.querySelector(".rt-keys") as HTMLElement
            keys.textContent!!.shouldContainString("half a star")
            keys.getAttribute("aria-hidden") shouldBe "true"
        }

        // --- Everyone ---

        test("the ListenUp score is a labelled button that names its sources") {
            val host =
                panel(ready(listeners = ListenerAverage(8.0, 3), external = SCORE, breakdown = listOf(AUDIBLE, HARDCOVER)))
            awaitFrame()

            val score = host.querySelector("button.rt-score") as HTMLButtonElement
            score.getAttribute("aria-label") shouldBe
                "ListenUp score: Rated 4.6 out of 5 stars from 12k ratings. Audible, Hardcover, your listeners."
            score.textContent!!.shouldContainString("12k ratings · Audible, Hardcover, your listeners")
        }

        // a11y audit M4 (Android, 2026-10-03), held on every platform: the score row must be something a
        // screen reader and a keyboard can activate — a native, enabled, focusable <button>, never a span
        // with role="button" — and at least 44 px tall (`.rt-er`'s 56 px min-height; the class contract
        // keeps it applied).
        test("screen readers and keyboards can activate the ListenUp score") {
            val state = ready(listeners = ListenerAverage(8.0, 3), external = SCORE, breakdown = listOf(AUDIBLE, HARDCOVER))
            // Inside the app's surface, so the sheet's `.luw` rules — the row's min-height — apply.
            val host =
                mounts.mount {
                    WebAppSurface { RatingsPanel(state = state, onSetStars = {}, onRate = { _, _ -> }, onClear = {}) }
                }
            awaitFrame()

            val score = host.querySelector(".rt-score") as HTMLElement
            score.tagName shouldBe "BUTTON"
            score.getAttribute("type") shouldBe "button"
            (score as HTMLButtonElement).disabled shouldBe false
            (score.tabIndex >= 0) shouldBe true
            score.getAttribute("role").shouldBeNull()
            (score.getBoundingClientRect().height >= 44.0) shouldBe true
            score.click()
            awaitPresent(document.body!!, "dialog .rt-srcs")
        }

        test("the score shows one decimal, never a dropped trailing zero, and never a half star") {
            val four = panel(ready(external = CombinedScore(average = 4.0, count = 12_000)))
            awaitFrame()
            // ⛔ "4.0", never "4": a JS double drops the trailing zero, and starsLabel would round a
            // continuous average to the nearest half star instead of printing it exactly.
            (four.querySelector("button.rt-score") as HTMLElement).getAttribute("aria-label") shouldBe
                "ListenUp score: Rated 4.0 out of 5 stars from 12k ratings."
            (four.querySelector("button.rt-score .rt-en") as HTMLElement).textContent shouldBe "★4.0"
            mounts.disposeAll()

            val halfUp = panel(ready(external = CombinedScore(average = 4.45, count = 1)))
            awaitFrame()
            (halfUp.querySelector("button.rt-score") as HTMLElement).getAttribute("aria-label") shouldBe
                "ListenUp score: Rated 4.5 out of 5 stars from 1 rating."
        }

        test("your listeners read to one decimal, with how many rated") {
            val host = panel(ready(listeners = ListenerAverage(8.0, 3), external = SCORE, breakdown = listOf(AUDIBLE)))
            awaitFrame()

            host.textContent!!.shouldContainString("Your listeners: 4.0 out of 5 stars, from 3 ratings")
            host.textContent!!.shouldContainString("3 ratings")
        }

        test("one listener's rating is read as one rating") {
            val host = panel(ready(listeners = ListenerAverage(averageHalfStars = 8.0, count = 1)))
            awaitFrame()

            host.textContent!!.shouldContainString("Your listeners: 4.0 out of 5 stars, from 1 rating")
        }

        test("while Hardcover is checked, a placeholder holds the score's row") {
            val host = panel(ready(mine = rating(9), listeners = ListenerAverage(9.0, 1), isCheckingExternal = true))
            awaitFrame()

            (host.querySelector(".rt-ev [role=status]") as HTMLElement)
                .textContent!!
                .shouldContainString("Checking Hardcover…")
            host.querySelector(".rt-skel").shouldNotBeNull()
            host.querySelector("button.rt-score").shouldBeNull()
        }

        test("when the check ends, the same live region says what it found") {
            var state by mutableStateOf(ready(isCheckingExternal = true))
            val host =
                mounts.mount {
                    RatingsPanel(state = state, onSetStars = {}, onRate = { _, _ -> }, onClear = {})
                }
            awaitFrame()
            val live = host.querySelector(".rt-ev [role=status]") as HTMLElement

            state = ready(external = SCORE, breakdown = listOf(AUDIBLE, HARDCOVER))
            awaitFrame()

            // The region is the one that was there all along: inserted with its text, a status is
            // announced unreliably, so it stays mounted and only its words change.
            host.querySelector(".rt-ev [role=status]") shouldBe live
            live.textContent shouldBe "ListenUp score: 4.6 out of 5 stars."
        }

        test("a score that was there before the check says nothing when it ends") {
            var state by mutableStateOf(ready(external = SCORE, breakdown = listOf(AUDIBLE), isCheckingExternal = true))
            val host =
                mounts.mount {
                    RatingsPanel(state = state, onSetStars = {}, onRate = { _, _ -> }, onClear = {})
                }
            awaitFrame()

            state = ready(external = SCORE, breakdown = listOf(AUDIBLE))
            awaitFrame()

            (host.querySelector(".rt-ev [role=status]") as HTMLElement).textContent shouldBe ""
        }

        test("no ratings anywhere: an admin can refresh, a listener sees only the line") {
            var refreshes = 0
            val admin = panel(ready(canRefresh = true), onRefreshExternal = { refreshes++ })
            awaitFrame()
            admin.textContent!!.shouldContainString("No ratings yet")
            button(admin, "Refresh ratings").shouldNotBeNull()!!.click()
            refreshes shouldBe 1
            mounts.disposeAll()

            val listener = panel(ready(canRefresh = false))
            awaitFrame()
            listener.textContent!!.shouldContainString("No ratings yet")
            button(listener, "Refresh ratings").shouldBeNull()
        }

        test("a refresh in flight is aria-disabled in place, ignores the press, and keeps focus") {
            var refreshes = 0
            val host = panel(ready(canRefresh = true, isRefreshingExternal = true), onRefreshExternal = { refreshes++ })
            awaitFrame()

            val busy = button(host, "Refreshing…").shouldNotBeNull()
            busy.getAttribute("aria-disabled") shouldBe "true"
            busy.hasAttribute("disabled") shouldBe false
            busy.focus()
            busy.click()

            refreshes shouldBe 0
            document.activeElement shouldBe busy
        }

        test("no inline refresh once a score exists: it lives in the sources view") {
            val host = panel(ready(external = SCORE, breakdown = listOf(AUDIBLE), canRefresh = true))
            awaitFrame()

            button(host, "Refresh ratings").shouldBeNull()
            host.querySelector("button.rt-score").shouldNotBeNull()
        }

        // --- A book only your listeners have rated ---

        val listenersOnly =
            ready(
                listeners = ListenerAverage(averageHalfStars = 9.0, count = 3),
                // The score reads 4.3 off the listeners' own curve onto ListenUp's: a different
                // number from the same three ratings.
                external = CombinedScore(average = 4.3, count = 3, shares = mapOf(ScoreSource.Listeners to 1.0)),
            )

        test("a book only your listeners rated shows their average once, not a second recalibrated number") {
            val host = panel(listenersOnly)
            awaitFrame()

            host.textContent!!.shouldContainString("Your listeners: 4.5 out of 5 stars, from 3 ratings")
            host.querySelector("button.rt-score").shouldBeNull()
            host.textContent!!.contains("No ratings yet") shouldBe false
        }

        test("an admin can still refresh a book only your listeners rated") {
            val host = panel(listenersOnly.copy(canRefresh = true))
            awaitFrame()

            button(host, "Refresh ratings").shouldNotBeNull()
        }

        // --- The sources dialog ---

        test("the sources dialog shows each source's share and freshness, with a quiet refresh") {
            val host =
                panel(
                    ready(
                        listeners = ListenerAverage(8.0, 3),
                        external = SCORE,
                        breakdown = listOf(AUDIBLE, HARDCOVER),
                        canRefresh = true,
                    ),
                    nowMs = 1_000L * DAY,
                )
            awaitFrame()

            val dialog = openSources(host)
            dialog.textContent!!.shouldContainString("Combined from 3 sources")
            dialog.textContent!!.shouldContainString("ListenUp score · 12k ratings")
            dialog.textContent!!.shouldContainString("62%")
            dialog.textContent!!.shouldContainString("Updated 3 days ago")
            dialog.querySelectorAll(".rt-upd").length shouldBe 1
            button(dialog, "Refresh ratings").shouldNotBeNull()!!.classList.contains("btn-ghost") shouldBe true
        }

        test("each source row is read as one sentence, not a run of figures") {
            val host =
                panel(
                    ready(listeners = ListenerAverage(8.0, 3), external = SCORE, breakdown = listOf(AUDIBLE, HARDCOVER)),
                    nowMs = 1_000L * DAY,
                )
            awaitFrame()

            spokenRows(openSources(host)) shouldContainExactly
                listOf(
                    "Audible: 4.8 average from 11k ratings, 62% of the score. Updated 3 days ago.",
                    "Hardcover: 4.5 average from 1.2k ratings, 30% of the score.",
                    "Your listeners: 4.0 average from 3 ratings, 8% of the score.",
                )
        }

        test("a score from one source does not say combined, and no listeners row without listeners") {
            val host =
                panel(
                    ready(
                        external =
                            CombinedScore(
                                average = 4.8,
                                count = 11_000,
                                shares = mapOf(ScoreSource.Outside(ExternalRatingSource.AUDIBLE) to 1.0),
                            ),
                        breakdown = listOf(AUDIBLE),
                    ),
                )
            awaitFrame()

            val dialog = openSources(host)
            dialog.textContent!!.contains("Combined from") shouldBe false
            spokenRows(dialog) shouldContainExactly listOf("Audible: 4.8 average from 11k ratings, 100% of the score.")
        }

        test("the sources dialog's refresh offers itself only to an admin, and is aria-disabled while busy") {
            val listener = panel(ready(external = SCORE, breakdown = listOf(AUDIBLE), canRefresh = false))
            awaitFrame()
            button(openSources(listener), "Refresh ratings").shouldBeNull()
            mounts.disposeAll()

            val busy = panel(ready(external = SCORE, breakdown = listOf(AUDIBLE), canRefresh = true, isRefreshingExternal = true))
            awaitFrame()
            val refreshing = button(openSources(busy), "Refreshing…").shouldNotBeNull()
            refreshing.getAttribute("aria-disabled") shouldBe "true"
            refreshing.hasAttribute("disabled") shouldBe false
        }

        test("staleness is counted in whole days, and says nothing without both times") {
            updatedLabel(fetchedAtMs = 10 * DAY, nowMs = 10 * DAY + 1) shouldBe "Updated today"
            updatedLabel(fetchedAtMs = 10 * DAY, nowMs = 11 * DAY + 1) shouldBe "Updated yesterday"
            updatedLabel(fetchedAtMs = 10 * DAY, nowMs = 13 * DAY) shouldBe "Updated 3 days ago"
            updatedLabel(fetchedAtMs = 20 * DAY, nowMs = 13 * DAY) shouldBe "Updated today"
            updatedLabel(fetchedAtMs = null, nowMs = 13 * DAY).shouldBeNull()
            updatedLabel(fetchedAtMs = 10 * DAY, nowMs = 0L).shouldBeNull()
        }

        // --- The stars ---

        test("the stars step one half per arrow key, and Home and End jump to one and five") {
            val host = stars(6)
            awaitFrame()
            val slider = slider(host)

            slider.getAttribute("aria-valuenow") shouldBe "3"
            slider.getAttribute("aria-valuetext") shouldBe "3 out of 5 stars"
            slider.getAttribute("aria-label") shouldBe "Rating"
            slider.getAttribute("tabindex") shouldBe "0"

            slider.press("ArrowRight")
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
            val host = stars(0)
            awaitFrame()
            val slider = slider(host)

            slider.getAttribute("aria-valuenow").shouldBeNull()
            slider.getAttribute("aria-valuetext") shouldBe "Not rated"

            slider.press("ArrowRight")
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

        test("a key step commits as well as previews") {
            val changes = mutableListOf<Int>()
            val commits = mutableListOf<Int>()
            val host =
                mounts.mount {
                    RatingStars(halfStars = 6, onHalfStarsChange = { changes += it }, onHalfStarsCommit = { commits += it })
                }
            awaitFrame()

            slider(host).press("ArrowRight")

            changes shouldContainExactly listOf(7)
            commits shouldContainExactly listOf(7)
        }

        test("a drag previews as it moves and commits only where it lets go") {
            val changes = mutableListOf<Int>()
            val commits = mutableListOf<Int>()
            val host =
                mounts.mount {
                    RatingStars(halfStars = 0, onHalfStarsChange = { changes += it }, onHalfStarsCommit = { commits += it })
                }
            awaitFrame()
            val stars = slider(host)

            stars.pointer("pointerdown", stars, 0.15)
            stars.pointer("pointermove", stars, 0.55)
            commits shouldBe emptyList()
            changes shouldContain 6

            stars.pointer("pointerup", stars, 0.55)
            clickAcross(stars, 0.55)

            commits shouldContainExactly listOf(6)
        }

        test("a move with no press first changes nothing") {
            val changes = mutableListOf<Int>()
            val host = mounts.mount { RatingStars(halfStars = 0, onHalfStarsChange = { changes += it }) }
            awaitFrame()
            val stars = slider(host)

            stars.pointer("pointermove", stars, 0.55)

            changes shouldBe emptyList()
        }

        test("the slider takes the name it is given, and says Rating when given none") {
            val named = mounts.mount { RatingStars(halfStars = 0, onHalfStarsChange = {}, label = "Your rating") }
            val plain = mounts.mount { RatingStars(halfStars = 0, onHalfStarsChange = {}) }
            awaitFrame()

            slider(named).getAttribute("aria-label") shouldBe "Your rating"
            slider(plain).getAttribute("aria-label") shouldBe "Rating"
        }

        // --- The note dialog ---

        test("Save is disabled until a star is chosen, then saves the rating and the trimmed note") {
            val saved = mutableListOf<Pair<Int, String?>>()
            val host =
                mounts.mount {
                    RateBookDialog(
                        open = true,
                        current = null,
                        onSave = { stars, note -> saved += stars to note },
                        onClear = {},
                        onDismiss = {},
                    )
                }
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

            saved shouldContainExactly listOf(10 to "Loved it.")
        }

        test("Edit note opens on your rating and note, and Remove rating clears it") {
            var cleared = 0
            val host = panel(ready(mine = rating(9, note = "Great")), onClear = { cleared++ })
            awaitFrame()
            button(host, "Edit note")!!.click()
            val dialog = awaitPresent(host, "dialog")

            dialog.querySelector("[role=slider]")!!.getAttribute("aria-valuenow") shouldBe "4.5"
            (dialog.querySelector("textarea") as HTMLTextAreaElement).value shouldBe "Great"
            (dialog.querySelector(".rt-count") as HTMLElement).textContent shouldBe "5/280"

            button(dialog, "Remove rating")!!.click()
            awaitGone(host, "dialog")

            cleared shouldBe 1
        }

        // --- Through the real ViewModel ---

        fun realSession(
            repository: FakeBookRatingRepository,
            isAdmin: Boolean = false,
        ): BookRatingsSession {
            val koin =
                koinApplication {
                    modules(
                        module {
                            factory { params ->
                                BookRatingsViewModel(
                                    bookId = params.get(),
                                    repository = repository,
                                    currentUserId = flowOf("me"),
                                    errorBus = ErrorBus(),
                                    userRepository = FakeUserRepository(isAdmin = isAdmin),
                                )
                            }
                        },
                    )
                }.koin
            return graphBookRatings(koin)("b1")
        }

        fun mountSession(session: BookRatingsSession): HTMLElement =
            mounts.mount {
                RatingsPanel(
                    state = session.state.collectAsState().value,
                    onSetStars = session.setStars,
                    onRate = session.rate,
                    onClear = session.clear,
                    onRefreshExternal = session.refreshExternal,
                )
            }

        test("a click on the stars reaches the real ViewModel, keeps your note, and the panel shows it") {
            val repository = FakeBookRatingRepository()
            repository.ratings.value = listOf(rating(4, note = "Slow start"))
            val session = realSession(repository)
            val host = mountSession(session)
            try {
                clickAcross(awaitPresent(host, "[role=slider]"), 0.75)

                withTimeout(RECOMPOSE_TIMEOUT_MS) { while (repository.rated.isEmpty()) delay(10) }
                repository.rated shouldContainExactly listOf(Triple("b1", 8, "Slow start"))
                withTimeout(RECOMPOSE_TIMEOUT_MS) {
                    while ((host.querySelector(".rt-you-v") as? HTMLElement)?.textContent != "4") delay(10)
                }
            } finally {
                session.close()
            }
        }

        test("Remove clears through the ViewModel, and Undo puts back the stars and the note") {
            val repo = FakeBookRatingRepository()
            repo.ratings.value = listOf(rating(8, note = "Loved it"))
            val session = realSession(repo)
            try {
                session.clear()
                val event = withTimeout(RECOMPOSE_TIMEOUT_MS) { session.events.first() }
                event shouldBe BookRatingsEvent.RatingRemoved
                repo.cleared shouldContainExactly listOf("b1")

                session.undoClear()
                withTimeout(RECOMPOSE_TIMEOUT_MS) { while (repo.rated.isEmpty()) delay(10) }
                repo.rated.last() shouldBe Triple("b1", 8, "Loved it")
            } finally {
                session.close()
            }
        }

        test("refresh reaches the real ViewModel's refreshExternal, admin-gated end to end, and stays busy until it answers") {
            val refreshGate = CompletableDeferred<Unit>()
            val repository =
                FakeBookRatingRepository(refreshGate = refreshGate).apply {
                    external.value = listOf(ExternalRating(source = ExternalRatingSource.AUDIBLE, average = 4.4, count = 100))
                }
            val session = realSession(repository, isAdmin = true)
            val host = mountSession(session)
            try {
                awaitPresent(host, "button.rt-score").click()
                val dialog = awaitPresent(document.body!!, "dialog .rt-srcs").closest("dialog") as HTMLElement
                button(dialog, "Refresh ratings")!!.click()

                withTimeout(RECOMPOSE_TIMEOUT_MS) { while (repository.refreshed.isEmpty()) delay(10) }
                repository.refreshed shouldContainExactly listOf("b1")

                // ⛔ The RPC hasn't answered yet (the gate is still open) — the button must say so.
                withTimeout(RECOMPOSE_TIMEOUT_MS) {
                    while (button(dialog, "Refreshing…")?.getAttribute("aria-disabled") != "true") delay(10)
                }

                refreshGate.complete(Unit)

                withTimeout(RECOMPOSE_TIMEOUT_MS) {
                    while (button(dialog, "Refresh ratings")?.hasAttribute("aria-disabled") != false) delay(10)
                }
            } finally {
                session.close()
            }
        }

        // --- Book Detail's route ---

        test("the route saves from the stars, says Rating removed with Undo, and Undo restores") {
            val saved = mutableListOf<Int>()
            var undone = 0
            val events = MutableSharedFlow<BookRatingsEvent>(replay = 1)
            val toasts = mutableListOf<Pair<String, ToastAction>>()
            val (host, router, composition) =
                mountAt(
                    "/book/b1",
                    openBookRatings =
                        fixedBookRatings(
                            ready(mine = rating(8)),
                            onSetStars = { saved += it },
                            onUndoClear = { undone++ },
                            events = events,
                        ),
                    onActionToast = { text, _, action -> toasts += text to action },
                )
            try {
                val stars = awaitPresent(host, ".rt-you [role=slider]")
                stars.press("ArrowRight")
                saved shouldContainExactly listOf(9)

                events.emit(BookRatingsEvent.RatingRemoved)
                withTimeout(RECOMPOSE_TIMEOUT_MS) { while (toasts.isEmpty()) delay(10) }
                val (text, undo) = toasts.single()
                text shouldBe "Rating removed"
                undo.label shouldBe "Undo"

                undo.onAction()
                undone shouldBe 1
                awaitFrame()
                document.activeElement shouldBe host.querySelector(".rt-you [role=slider]")
            } finally {
                composition.dispose()
                router.dispose()
            }
        }

        test("the library sorts books by the outside world's rating and by your listeners'") {
            BOOK_SORT_CATEGORIES shouldContain SortCategory.RATING
            BOOK_SORT_CATEGORIES shouldContain SortCategory.LISTENER_RATING
            BOOK_SORT_CATEGORIES.indexOf(SortCategory.LISTENER_RATING) shouldBe
                BOOK_SORT_CATEGORIES.indexOf(SortCategory.ADDED) + 2
        }
    })
