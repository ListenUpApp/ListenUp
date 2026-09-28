package com.calypsan.listenup.client.presentation.bookdetail

import app.cash.turbine.test
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.ExternalRating
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.domain.repository.BookRatingRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** A [UserRepository] mock stubbed with a fixed [isAdmin] answer for [UserRepository.observeIsAdmin]. */
private fun userRepository(isAdmin: Boolean = false): UserRepository =
    mock<UserRepository>().also { every { it.observeIsAdmin() } returns flowOf(isAdmin) }

/**
 * Tests for [BookRatingsViewModel]. Pins the sealed [BookRatingsUiState] mapping over
 * [BookRatingRepository.observeForBook]: Loading first, then a [BookRatingsUiState.Ready] whose
 * `listeners` average is null when nobody has rated the book and whose `mine` is the signed-in
 * listener's own rating (or null). Also pins that [BookRatingsViewModel.rate] and
 * [BookRatingsViewModel.clear] delegate to the repository, and that a repository failure reaches
 * [ErrorBus].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BookRatingsViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        test("Ready carries the listeners' average and my own rating") {
            runTest {
                val repo = FakeBookRatingRepository()
                repo.seed(ListenerRating("b1", "me", 8, "Mine.", 1L), ListenerRating("b1", "ann", 6, null, 1L))
                val vm =
                    BookRatingsViewModel(
                        bookId = "b1",
                        repository = repo,
                        currentUserId = flowOf("me"),
                        errorBus = ErrorBus(),
                        userRepository = userRepository(),
                    )

                vm.state.test {
                    awaitItem() shouldBe BookRatingsUiState.Loading
                    val ready = awaitItem().shouldBeInstanceOf<BookRatingsUiState.Ready>()
                    ready.listeners shouldBe ListenerAverage(7.0, 2)
                    ready.mine?.halfStars shouldBe 8
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("nobody has rated: no average, no rating of mine") {
            runTest {
                val repo = FakeBookRatingRepository()
                val vm =
                    BookRatingsViewModel(
                        bookId = "b1",
                        repository = repo,
                        currentUserId = flowOf("me"),
                        errorBus = ErrorBus(),
                        userRepository = userRepository(),
                    )

                vm.state.test {
                    awaitItem() shouldBe BookRatingsUiState.Loading
                    val ready = awaitItem().shouldBeInstanceOf<BookRatingsUiState.Ready>()
                    ready.listeners.shouldBeNull()
                    ready.mine.shouldBeNull()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("rate and clear go through the repository; a failure reaches the error bus") {
            runTest {
                val repo = FakeBookRatingRepository()
                val errorBus = ErrorBus()
                val vm =
                    BookRatingsViewModel(
                        bookId = "b1",
                        repository = repo,
                        currentUserId = flowOf("me"),
                        errorBus = errorBus,
                        userRepository = userRepository(),
                    )

                vm.rate(8, "Great listen.")
                advanceUntilIdle()
                repo.lastRate shouldBe Triple("b1", 8, "Great listen.")

                vm.clear()
                advanceUntilIdle()
                repo.lastClear shouldBe "b1"

                val failure = TransportError.NetworkUnavailable()
                repo.failNext = AppResult.Failure(failure)
                errorBus.errors.test {
                    vm.rate(6, null)
                    advanceUntilIdle()
                    awaitItem() shouldBe failure
                }
            }
        }

        test("Ready carries the outside headline and its per-source breakdown") {
            runTest {
                val repo = FakeBookRatingRepository()
                repo.seedExternal(
                    ExternalRating(source = ExternalRatingSource.AUDIBLE, average = 4.0, count = 100),
                    ExternalRating(source = ExternalRatingSource.HARDCOVER, average = 5.0, count = 300),
                )
                val vm =
                    BookRatingsViewModel(
                        bookId = "b1",
                        repository = repo,
                        currentUserId = flowOf("me"),
                        errorBus = ErrorBus(),
                        userRepository = userRepository(),
                    )

                vm.state.test {
                    awaitItem() shouldBe BookRatingsUiState.Loading
                    val ready = awaitItem().shouldBeInstanceOf<BookRatingsUiState.Ready>()
                    ready.external shouldBe CombinedScore(average = 4.75, count = 400)
                    ready.breakdown.map { it.source } shouldBe listOf(ExternalRatingSource.AUDIBLE, ExternalRatingSource.HARDCOVER)
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("no outside source has rated the book: no headline, empty breakdown") {
            runTest {
                val repo = FakeBookRatingRepository()
                val vm =
                    BookRatingsViewModel(
                        bookId = "b1",
                        repository = repo,
                        currentUserId = flowOf("me"),
                        errorBus = ErrorBus(),
                        userRepository = userRepository(),
                    )

                vm.state.test {
                    awaitItem() shouldBe BookRatingsUiState.Loading
                    val ready = awaitItem().shouldBeInstanceOf<BookRatingsUiState.Ready>()
                    ready.external.shouldBeNull()
                    ready.breakdown shouldBe emptyList()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("canRefresh is true for an admin, false for an ordinary listener") {
            runTest {
                val repo = FakeBookRatingRepository()

                val admin =
                    BookRatingsViewModel(
                        bookId = "b1",
                        repository = repo,
                        currentUserId = flowOf("me"),
                        errorBus = ErrorBus(),
                        userRepository = userRepository(isAdmin = true),
                    )
                admin.state.test {
                    awaitItem() shouldBe BookRatingsUiState.Loading
                    awaitItem().shouldBeInstanceOf<BookRatingsUiState.Ready>().canRefresh shouldBe true
                    cancelAndIgnoreRemainingEvents()
                }

                val listener =
                    BookRatingsViewModel(
                        bookId = "b1",
                        repository = repo,
                        currentUserId = flowOf("me"),
                        errorBus = ErrorBus(),
                        userRepository = userRepository(isAdmin = false),
                    )
                listener.state.test {
                    awaitItem() shouldBe BookRatingsUiState.Loading
                    awaitItem().shouldBeInstanceOf<BookRatingsUiState.Ready>().canRefresh shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("refreshExternal goes through the repository; a failure reaches the error bus") {
            runTest {
                val repo = FakeBookRatingRepository()
                val errorBus = ErrorBus()
                val vm =
                    BookRatingsViewModel(
                        bookId = "b1",
                        repository = repo,
                        currentUserId = flowOf("me"),
                        errorBus = errorBus,
                        userRepository = userRepository(isAdmin = true),
                    )

                vm.refreshExternal()
                advanceUntilIdle()
                repo.lastRefreshExternal shouldBe "b1"

                val failure = TransportError.NetworkUnavailable()
                repo.refreshExternalResult = AppResult.Failure(failure)
                errorBus.errors.test {
                    vm.refreshExternal()
                    advanceUntilIdle()
                    awaitItem() shouldBe failure
                }
            }
        }
    })

/**
 * In-memory fake of [BookRatingRepository] for [BookRatingsViewModel] seam tests. `rate` and
 * `clear` are attributed to a hard-coded "me" — every test in this file uses `currentUserId =
 * flowOf("me")`, so the fake never needs its own notion of who is signed in.
 */
private class FakeBookRatingRepository : BookRatingRepository {
    private val ratingsFlow = MutableStateFlow<List<ListenerRating>>(emptyList())
    private val externalFlow = MutableStateFlow<List<ExternalRating>>(emptyList())

    /** The (bookId, halfStars, note) passed to the last [rate] call. */
    var lastRate: Triple<String, Int, String?>? = null

    /** The bookId passed to the last [clear] call. */
    var lastClear: String? = null

    /** The bookId passed to the last [refreshExternal] call. */
    var lastRefreshExternal: String? = null

    /** When set, consumed by the next [rate] or [clear] call in place of succeeding. */
    var failNext: AppResult.Failure? = null

    /** What the next [refreshExternal] call answers. */
    var refreshExternalResult: AppResult<Unit> = AppResult.Success(Unit)

    fun seed(vararg rows: ListenerRating) {
        ratingsFlow.value = rows.toList()
    }

    fun seedExternal(vararg rows: ExternalRating) {
        externalFlow.value = rows.toList()
    }

    override fun observeForBook(bookId: String): Flow<List<ListenerRating>> =
        ratingsFlow.map { rows -> rows.filter { it.bookId == bookId } }

    override fun observeAverages(): Flow<Map<String, ListenerAverage>> =
        ratingsFlow.map { rows ->
            rows.groupBy { it.bookId }.mapValues { (_, rs) ->
                ListenerAverage(averageHalfStars = rs.map { it.halfStars }.average(), count = rs.size)
            }
        }

    override suspend fun rate(
        bookId: String,
        halfStars: Int,
        note: String?,
    ): AppResult<Unit> {
        lastRate = Triple(bookId, halfStars, note)
        failNext?.let {
            failNext = null
            return it
        }
        ratingsFlow.value =
            ratingsFlow.value.filterNot { it.bookId == bookId && it.userId == "me" } +
            ListenerRating(bookId = bookId, userId = "me", halfStars = halfStars, note = note, ratedAtMs = 0L)
        return AppResult.Success(Unit)
    }

    override suspend fun clear(bookId: String): AppResult<Unit> {
        lastClear = bookId
        failNext?.let {
            failNext = null
            return it
        }
        ratingsFlow.value = ratingsFlow.value.filterNot { it.bookId == bookId && it.userId == "me" }
        return AppResult.Success(Unit)
    }

    override fun observeExternalForBook(bookId: String): Flow<List<ExternalRating>> = externalFlow

    override fun observeCombinedScores(): Flow<Map<String, CombinedScore>> = flowOf(emptyMap())

    override suspend fun refreshExternal(bookId: String): AppResult<Unit> {
        lastRefreshExternal = bookId
        return refreshExternalResult
    }
}
