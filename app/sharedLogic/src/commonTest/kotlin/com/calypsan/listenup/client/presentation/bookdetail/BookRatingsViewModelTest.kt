package com.calypsan.listenup.client.presentation.bookdetail

import app.cash.turbine.test
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.domain.model.CombinedScore
import com.calypsan.listenup.client.domain.model.ExternalRating
import com.calypsan.listenup.client.domain.model.ListenerAverage
import com.calypsan.listenup.client.domain.model.ListenerRating
import com.calypsan.listenup.client.domain.model.ScoreSource
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.time.Duration.Companion.seconds

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

        test("Ready carries the repository's ListenUp score, its shares, and the per-source breakdown") {
            runTest {
                val repo = FakeBookRatingRepository()
                repo.seedExternal(
                    ExternalRating(source = ExternalRatingSource.AUDIBLE, average = 4.0, count = 100),
                    ExternalRating(source = ExternalRatingSource.HARDCOVER, average = 5.0, count = 300),
                )
                // The library-calibrated score the Rating sort uses too — the headline never
                // recomputes it from this book's rows alone, so the two always agree.
                val score =
                    CombinedScore(
                        average = 4.41,
                        count = 403,
                        shares =
                            mapOf(
                                ScoreSource.Outside(ExternalRatingSource.AUDIBLE) to 0.3,
                                ScoreSource.Outside(ExternalRatingSource.HARDCOVER) to 0.5,
                                ScoreSource.Listeners to 0.2,
                            ),
                    )
                repo.seedCombined("b1", score)
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
                    ready.external shouldBe score
                    ready.external?.sourceCount shouldBe 3
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

        test("a refresh shows as in flight until it answers, even when nothing changed, and a second tap waits") {
            runTest {
                val repo = FakeBookRatingRepository()
                val gate = CompletableDeferred<Unit>()
                repo.refreshGate = gate
                val vm =
                    BookRatingsViewModel(
                        bookId = "b1",
                        repository = repo,
                        currentUserId = flowOf("me"),
                        errorBus = ErrorBus(),
                        userRepository = userRepository(isAdmin = true),
                    )

                vm.state.test {
                    awaitItem().shouldBeInstanceOf<BookRatingsUiState.Loading>()
                    awaitItem().shouldBeInstanceOf<BookRatingsUiState.Ready>().isRefreshingExternal shouldBe false

                    vm.refreshExternal()
                    advanceUntilIdle()
                    awaitItem().shouldBeInstanceOf<BookRatingsUiState.Ready>().isRefreshingExternal shouldBe true

                    vm.refreshExternal()
                    advanceUntilIdle()
                    repo.refreshExternalCalls shouldBe 1

                    gate.complete(Unit)
                    advanceUntilIdle()
                    awaitItem().shouldBeInstanceOf<BookRatingsUiState.Ready>().isRefreshingExternal shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("opening a book asks the server, once, to check its outside ratings") {
            runTest {
                val repo = FakeBookRatingRepository()
                BookRatingsViewModel(
                    bookId = "b1",
                    repository = repo,
                    currentUserId = flowOf("me"),
                    errorBus = ErrorBus(),
                    userRepository = userRepository(),
                )
                advanceUntilIdle()

                repo.checked shouldBe listOf("b1")
            }
        }

        test("isCheckingExternal holds while the on-open fetch runs, and clears when it is done") {
            runTest {
                val gate = CompletableDeferred<Unit>()
                val repo =
                    FakeBookRatingRepository().apply {
                        externalCheck =
                            flow {
                                emit(true)
                                gate.await()
                                emit(false)
                            }
                    }
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
                    runCurrent()
                    expectMostRecentItem().shouldBeInstanceOf<BookRatingsUiState.Ready>().isCheckingExternal shouldBe true

                    gate.complete(Unit)
                    runCurrent()
                    awaitItem().shouldBeInstanceOf<BookRatingsUiState.Ready>().isCheckingExternal shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a check that never answers stops holding the row after thirty seconds") {
            runTest {
                val repo =
                    FakeBookRatingRepository().apply {
                        externalCheck =
                            flow {
                                emit(true)
                                awaitCancellation()
                            }
                    }
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
                    runCurrent()
                    expectMostRecentItem().shouldBeInstanceOf<BookRatingsUiState.Ready>().isCheckingExternal shouldBe true

                    advanceTimeBy(29.seconds)
                    runCurrent()
                    expectNoEvents()

                    advanceTimeBy(2.seconds)
                    runCurrent()
                    awaitItem().shouldBeInstanceOf<BookRatingsUiState.Ready>().isCheckingExternal shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("no check needed, or a failed one, shows nothing and reports nothing") {
            runTest {
                val repo = FakeBookRatingRepository().apply { externalCheck = emptyFlow() }
                val errorBus = ErrorBus()
                val vm =
                    BookRatingsViewModel(
                        bookId = "b1",
                        repository = repo,
                        currentUserId = flowOf("me"),
                        errorBus = errorBus,
                        userRepository = userRepository(),
                    )

                errorBus.errors.test {
                    advanceUntilIdle()
                    expectNoEvents()
                }
                vm.state.test {
                    awaitItem() shouldBe BookRatingsUiState.Loading
                    awaitItem().shouldBeInstanceOf<BookRatingsUiState.Ready>().isCheckingExternal shouldBe false
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a tap on the stars saves at once and keeps the note") {
            runTest {
                val repo = FakeBookRatingRepository()
                repo.seed(ListenerRating("b1", "me", 6, "Mine.", 1L))
                val vm =
                    BookRatingsViewModel(
                        bookId = "b1",
                        repository = repo,
                        currentUserId = flowOf("me"),
                        errorBus = ErrorBus(),
                        userRepository = userRepository(),
                    )

                vm.setStars(9)
                advanceUntilIdle()

                repo.lastRate shouldBe Triple("b1", 9, "Mine.")
                vm.state.test {
                    awaitItem() shouldBe BookRatingsUiState.Loading
                    val mine = awaitItem().shouldBeInstanceOf<BookRatingsUiState.Ready>().mine
                    mine?.halfStars shouldBe 9
                    mine?.note shouldBe "Mine."
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("the stars show the new rating before the save lands") {
            runTest {
                val gate = CompletableDeferred<Unit>()
                val repo = FakeBookRatingRepository().apply { rateGate = gate }
                repo.seed(ListenerRating("b1", "me", 6, null, 1L))
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
                    awaitItem().shouldBeInstanceOf<BookRatingsUiState.Ready>().mine?.halfStars shouldBe 6

                    vm.setStars(9)
                    runCurrent()
                    awaitItem().shouldBeInstanceOf<BookRatingsUiState.Ready>().mine?.halfStars shouldBe 9

                    gate.complete(Unit)
                    advanceUntilIdle()
                    vm.state.value
                        .shouldBeInstanceOf<BookRatingsUiState.Ready>()
                        .mine
                        ?.halfStars shouldBe 9
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a first rating shows at once, with no note") {
            runTest {
                val gate = CompletableDeferred<Unit>()
                val repo = FakeBookRatingRepository().apply { rateGate = gate }
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
                    awaitItem().shouldBeInstanceOf<BookRatingsUiState.Ready>().mine.shouldBeNull()

                    vm.setStars(7)
                    runCurrent()
                    val mine = awaitItem().shouldBeInstanceOf<BookRatingsUiState.Ready>().mine
                    mine?.halfStars shouldBe 7
                    mine?.note.shouldBeNull()
                    gate.complete(Unit)
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("a refused save puts the stars back and reports the error") {
            runTest {
                val gate = CompletableDeferred<Unit>()
                val failure = TransportError.NetworkUnavailable()
                val repo =
                    FakeBookRatingRepository().apply {
                        rateGate = gate
                        failNext = AppResult.Failure(failure)
                    }
                repo.seed(ListenerRating("b1", "me", 6, null, 1L))
                val errorBus = ErrorBus()
                val vm =
                    BookRatingsViewModel(
                        bookId = "b1",
                        repository = repo,
                        currentUserId = flowOf("me"),
                        errorBus = errorBus,
                        userRepository = userRepository(),
                    )

                errorBus.errors.test {
                    vm.state.test {
                        awaitItem() shouldBe BookRatingsUiState.Loading
                        awaitItem().shouldBeInstanceOf<BookRatingsUiState.Ready>().mine?.halfStars shouldBe 6

                        vm.setStars(9)
                        runCurrent()
                        awaitItem().shouldBeInstanceOf<BookRatingsUiState.Ready>().mine?.halfStars shouldBe 9

                        gate.complete(Unit)
                        advanceUntilIdle()
                        awaitItem().shouldBeInstanceOf<BookRatingsUiState.Ready>().mine?.halfStars shouldBe 6
                        cancelAndIgnoreRemainingEvents()
                    }
                    awaitItem() shouldBe failure
                }
            }
        }

        test("removing your rating offers an undo that puts back the stars and the note") {
            runTest {
                val repo = FakeBookRatingRepository()
                repo.seed(ListenerRating("b1", "me", 8, "Loved it.", 1L))
                val vm =
                    BookRatingsViewModel(
                        bookId = "b1",
                        repository = repo,
                        currentUserId = flowOf("me"),
                        errorBus = ErrorBus(),
                        userRepository = userRepository(),
                    )

                vm.events.test {
                    vm.clear()
                    advanceUntilIdle()
                    awaitItem() shouldBe BookRatingsEvent.RatingRemoved
                }
                repo.lastClear shouldBe "b1"

                vm.undoClear()
                advanceUntilIdle()

                repo.lastRate shouldBe Triple("b1", 8, "Loved it.")
            }
        }

        test("a second undo does nothing") {
            runTest {
                val repo = FakeBookRatingRepository()
                repo.seed(ListenerRating("b1", "me", 8, null, 1L))
                val vm =
                    BookRatingsViewModel(
                        bookId = "b1",
                        repository = repo,
                        currentUserId = flowOf("me"),
                        errorBus = ErrorBus(),
                        userRepository = userRepository(),
                    )

                vm.clear()
                advanceUntilIdle()
                vm.undoClear()
                vm.undoClear()
                advanceUntilIdle()

                repo.rateCalls shouldBe 1
            }
        }

        test("a refused removal reports the error and offers no undo") {
            runTest {
                val failure = TransportError.NetworkUnavailable()
                val repo = FakeBookRatingRepository().apply { failNext = AppResult.Failure(failure) }
                repo.seed(ListenerRating("b1", "me", 8, null, 1L))
                val errorBus = ErrorBus()
                val vm =
                    BookRatingsViewModel(
                        bookId = "b1",
                        repository = repo,
                        currentUserId = flowOf("me"),
                        errorBus = errorBus,
                        userRepository = userRepository(),
                    )

                errorBus.errors.test {
                    vm.events.test {
                        vm.clear()
                        advanceUntilIdle()
                        expectNoEvents()
                    }
                    awaitItem() shouldBe failure
                }
                vm.undoClear()
                advanceUntilIdle()
                repo.rateCalls shouldBe 0
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
    private val combinedFlow = MutableStateFlow<Map<String, CombinedScore>>(emptyMap())

    /** The (bookId, halfStars, note) passed to the last [rate] call. */
    var lastRate: Triple<String, Int, String?>? = null

    /** The bookId passed to the last [clear] call. */
    var lastClear: String? = null

    /** The bookId passed to the last [refreshExternal] call. */
    var lastRefreshExternal: String? = null

    /** When set, consumed by the next [rate] or [clear] call in place of succeeding. */
    var failNext: AppResult.Failure? = null

    /** How many times [refreshExternal] has been called. */
    var refreshExternalCalls = 0

    /** When set, [refreshExternal] waits on it before answering — a refresh still in flight. */
    var refreshGate: CompletableDeferred<Unit>? = null

    /** How many times [rate] has been called. */
    var rateCalls = 0

    /** When set, [rate] waits on it before answering — a save still in flight. */
    var rateGate: CompletableDeferred<Unit>? = null

    /** What the next [refreshExternal] call answers. */
    var refreshExternalResult: AppResult<Unit> = AppResult.Success(Unit)

    /** Every bookId [observeExternalCheck] was asked for. */
    val checked = mutableListOf<String>()

    /** What [observeExternalCheck] answers: nothing to check, by default. */
    var externalCheck: Flow<Boolean> = emptyFlow()

    override fun observeExternalCheck(bookId: String): Flow<Boolean> {
        checked += bookId
        return externalCheck
    }

    fun seed(vararg rows: ListenerRating) {
        ratingsFlow.value = rows.toList()
    }

    fun seedExternal(vararg rows: ExternalRating) {
        externalFlow.value = rows.toList()
    }

    fun seedCombined(
        bookId: String,
        score: CombinedScore,
    ) {
        combinedFlow.value += bookId to score
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
        rateGate?.await()
        rateCalls++
        lastRate = Triple(bookId, halfStars, note)
        failNext?.let { error ->
            failNext = null
            return error
        }
        ratingsFlow.value =
            ratingsFlow.value.filterNot { it.bookId == bookId && it.userId == "me" } +
            ListenerRating(bookId = bookId, userId = "me", halfStars = halfStars, note = note, ratedAtMs = 0L)
        return AppResult.Success(Unit)
    }

    override suspend fun clear(bookId: String): AppResult<Unit> {
        lastClear = bookId
        failNext?.let { error ->
            failNext = null
            return error
        }
        ratingsFlow.value = ratingsFlow.value.filterNot { it.bookId == bookId && it.userId == "me" }
        return AppResult.Success(Unit)
    }

    override fun observeExternalForBook(bookId: String): Flow<List<ExternalRating>> = externalFlow

    override fun observeCombinedScores(): Flow<Map<String, CombinedScore>> = combinedFlow

    override fun observeCombinedScore(bookId: String): Flow<CombinedScore?> = combinedFlow.map { it[bookId] }

    override suspend fun refreshExternal(bookId: String): AppResult<Unit> {
        lastRefreshExternal = bookId
        refreshExternalCalls++
        refreshGate?.await()
        return refreshExternalResult
    }
}
