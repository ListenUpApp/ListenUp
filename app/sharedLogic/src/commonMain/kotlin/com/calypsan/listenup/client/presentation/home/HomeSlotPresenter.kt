package com.calypsan.listenup.client.presentation.home

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.domain.model.ContinueListeningBook
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.model.NextInSeries
import com.calypsan.listenup.client.domain.model.PlaybackPosition
import com.calypsan.listenup.client.domain.model.signedInUserId
import com.calypsan.listenup.client.domain.repository.ActivityRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.BookAvailability
import com.calypsan.listenup.client.domain.repository.BookRatingRepository
import com.calypsan.listenup.client.domain.repository.BookRepository
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.InboxRepository
import com.calypsan.listenup.client.domain.repository.LoadedBookRepository
import com.calypsan.listenup.client.domain.repository.PlaybackPositionRepository
import com.calypsan.listenup.client.domain.repository.PlaybackUpdate
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.currentEpochMilliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn

/** The slot and the rail beside it, as Home's ViewModel folds them into `Ready`. */
internal data class HomeSlotState(
    val slot: HomeSlot,
    val everythingInProgress: List<ContinueListeningItem>,
)

/**
 * Home's one slot (spec §2.1–§2.3). It decides the kind from the loaded book, positions and Continue Listening (§2.2),
 * holds it until a decision point (§2.1), and keeps the held kind's contents live. Room only, so it answers offline.
 * Internal: [HomeViewModel] is its only consumer.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class HomeSlotPresenter(
    private val loadedBooks: LoadedBookRepository,
    private val positions: PlaybackPositionRepository,
    private val inbox: InboxRepository,
    private val nextInSeries: (bookId: String) -> Flow<NextInSeries?>,
    private val books: BookRepository,
    private val availability: BookAvailability,
    private val ratings: BookRatingRepository,
    private val activities: ActivityRepository,
    private val authSession: AuthSession,
    private val home: HomeRepository,
    private val nowMs: () -> Long = { currentEpochMilliseconds() },
) {
    /**
     * The slot and its rail, or null until the loaded book has been restored — so no Resume flashes in on a cold start
     * and then vanishes (spec §4.1 loading guard). [isLibraryArriving] is true while the library's first population or
     * this device's first catch-up is running. [decisionRuns] moves on every refresh and dismissal (§2.1 rules 1,
     * 3); [dismissedHere] maps a book to the finish dismissed this visit, before Room has caught up.
     */
    fun observe(
        continueListening: Flow<List<ContinueListeningItem>>,
        isLibraryArriving: Flow<Boolean>,
        decisionRuns: Flow<Int>,
        dismissedHere: Flow<Map<String, Long>>,
    ): Flow<HomeSlotState?> =
        channelFlow {
            // One upstream subscription: the kind and the contents read the same decided value.
            val decided =
                decide(
                    continueListening = continueListening,
                    isLibraryArriving = isLibraryArriving,
                    decisionRuns = decisionRuns,
                    dismissedHere = dismissedHere,
                ).stateIn(this)
            decided
                .map { decision -> decision?.run { latched.kind } }
                .distinctUntilChanged()
                .flatMapLatest { kind -> if (kind == null) flowOf(null) else assemble(kind, decided) }
                .collect { send(it) }
        }

    /** Dismiss [bookId]'s card on every device (S2): a position write that claims no listening. */
    suspend fun dismissFinished(bookId: String): AppResult<Unit> =
        positions.savePlaybackState(BookId(bookId), PlaybackUpdate.DismissFinishedCard(atMs = nowMs()))

    /** Rate [bookId] from its card on Now Playing's scale: [halfStars] 2..10. */
    suspend fun rateFinished(
        bookId: String,
        halfStars: Int,
    ): AppResult<Unit> = ratings.rate(bookId, halfStars, note = null)

    private fun decide(
        continueListening: Flow<List<ContinueListeningItem>>,
        isLibraryArriving: Flow<Boolean>,
        decisionRuns: Flow<Int>,
        dismissedHere: Flow<Map<String, Long>>,
    ): Flow<Decided?> {
        val loaded =
            loadedBooks
                .observe()
                .map { it.toKey() }
                .distinctUntilChanged()
                .scan<LoadedKey, LoadedView?>(null) { previous, key -> nextLoadedView(previous, key, nowMs()) }
        val facts =
            combine(positions.observeAll(), inbox.observeHeldBookIds(), dismissedHere) { all, held, dismissed ->
                SlotFacts(all.values.toList(), held.mapTo(HashSet()) { it.value }, dismissed)
            }
        return combine(
            flow = loaded,
            flow2 = continueListening,
            flow3 = facts,
            flow4 = isLibraryArriving,
            flow5 = decisionRuns,
        ) { view, items, f, building, runs ->
            view?.let { Candidate(view = it, items = items, facts = f, building = building, runs = runs) }
        }.scan<Candidate?, Decided?>(null) { previous, candidate ->
            candidate?.let { c ->
                val justFinished =
                    justFinishedBookId(
                        positions = c.facts.positions,
                        heldBookIds = c.facts.held,
                        dismissedHere = c.facts.dismissedHere,
                        freshLoad = c.view.freshLoad,
                        nowMs = nowMs(),
                    )
                val fresh =
                    slotKindFor(
                        SlotInputs(
                            loaded = c.view.key,
                            continueListening = c.items,
                            justFinishedBookId = justFinished,
                            hasHistory = c.facts.positions.isNotEmpty(),
                            isLibraryArriving = c.building,
                        ),
                    )
                val latched =
                    latch(
                        previous = previous?.latched,
                        point = DecisionPoint(c.runs, c.view.key),
                        fresh = fresh,
                        isProvisional = isStillArriving(c.items),
                    )
                Decided(latched, c.items, everythingInProgressOf(c.items, c.view.key, latched.kind))
            }
        }
    }

    private fun assemble(
        kind: SlotKind,
        decided: StateFlow<Decided?>,
    ): Flow<HomeSlotState?> {
        val current = decided.filterNotNull().filter { it.latched.kind == kind }
        return when (kind) {
            SlotKind.Absent -> {
                current.map { HomeSlotState(HomeSlot.Absent, it.everythingInProgress) }
            }

            SlotKind.FirstSync -> {
                current.map { HomeSlotState(HomeSlot.FirstSync, it.everythingInProgress) }
            }

            is SlotKind.Resume -> {
                resume(kind.bookId, current)
            }

            is SlotKind.JustFinished -> {
                combine(current, finishedCard(kind.bookId)) { d, card ->
                    HomeSlotState(card?.let(HomeSlot::JustFinished) ?: HomeSlot.Absent, d.everythingInProgress)
                }
            }

            SlotKind.NothingInProgress -> {
                combine(current, seriesCandidate()) { d, candidate ->
                    HomeSlotState(HomeSlot.NothingInProgress(candidate), d.everythingInProgress)
                }
            }

            SlotKind.NewListener -> {
                combine(current, home.observeLibraryShowcase(NEW_LISTENER_COVERS)) { d, showcase ->
                    HomeSlotState(HomeSlot.NewListener(showcase.covers), d.everythingInProgress)
                }
            }
        }
    }

    /** Resume's live contents. Its book follows Continue Listening, and keeps the last one seen if it leaves before the next decision. */
    private fun resume(
        bookId: String,
        current: Flow<Decided>,
    ): Flow<HomeSlotState?> {
        val extras =
            combine(
                nextInSeries(bookId),
                books.observeChapters(bookId),
                availability.observe(BookId(bookId)),
            ) { next, chapters, state ->
                ResumeExtras(next, chapters, state.canPlay)
            }
        return combine(current, extras) { d, x -> d to x }
            .scan<Pair<Decided, ResumeExtras>, HomeSlotState?>(null) { previous, (d, x) ->
                val book = d.readyBook(bookId) ?: (previous?.slot as? HomeSlot.Resume)?.book ?: return@scan previous
                HomeSlotState(
                    HomeSlot.Resume(
                        book = book,
                        next = x.next,
                        chapter = heroChapterOf(x.chapters, book.currentPositionMs),
                        canPlay = x.canPlay,
                    ),
                    d.everythingInProgress,
                )
            }.filterNotNull()
    }

    private fun finishedCard(bookId: String): Flow<FinishedCard?> {
        val me = authSession.authState.signedInUserId()
        val yourHalfStars =
            combine(ratings.observeForBook(bookId), me) { list, id -> list.firstOrNull { it.userId == id }?.halfStars }
        val alsoFinished =
            me
                .flatMapLatest { id -> activities.observeOtherFinishers(bookId, excludeUserId = id.orEmpty()) }
                .map(::alsoFinishedOf)
        return combine(
            flow = books.observeBookListItems(listOf(bookId)).map { it.firstOrNull() },
            flow2 = positions.observe(BookId(bookId)),
            flow3 = yourHalfStars,
            flow4 = nextInSeries(bookId),
            flow5 = alsoFinished,
        ) { item, position, stars, next, others ->
            val finishedAt = position?.finishedAtMs
            if (item == null || finishedAt == null) {
                null
            } else {
                FinishedCard(
                    book =
                        FinishedBook(
                            bookId = item.id.value,
                            title = item.title,
                            authorNames = item.authorNames,
                            coverPath = item.coverPath,
                            coverHash = item.coverHash,
                            durationMs = item.duration,
                        ),
                    startedAtMs = position.startedAtMs,
                    finishedAtMs = finishedAt,
                    yourHalfStars = stars,
                    next = next,
                    alsoFinished = others,
                )
            }
        }
    }

    private fun seriesCandidate(): Flow<NextInSeries?> =
        positions
            .observeAll()
            .map { all -> all.mapKeys { (id, _) -> id.value } }
            .flatMapLatest { byId ->
                val walk = finishedNewestFirst(byId.values)
                if (walk.isEmpty()) {
                    flowOf(null)
                } else {
                    combine(walk.map(nextInSeries)) { nexts -> seriesCandidateOf(nexts.toList(), byId) }
                }
            }.distinctUntilChanged()
}

/** The facts the just-finished pick and the history test read, gathered from three sources. */
private data class SlotFacts(
    val positions: List<PlaybackPosition>,
    val held: Set<String>,
    val dismissedHere: Map<String, Long>,
)

/** One emission's inputs, once the loaded book is known. */
private data class Candidate(
    val view: LoadedView,
    val items: List<ContinueListeningItem>,
    val facts: SlotFacts,
    val building: Boolean,
    val runs: Int,
)

/** The held kind, and the live lists its contents read. */
private data class Decided(
    val latched: LatchedSlot,
    val continueListening: List<ContinueListeningItem>,
    val everythingInProgress: List<ContinueListeningItem>,
) {
    fun readyBook(bookId: String): ContinueListeningBook? =
        continueListening
            .filterIsInstance<ContinueListeningItem.Ready>()
            .firstOrNull { it.bookId == bookId }
            ?.book
}

private data class ResumeExtras(
    val next: NextInSeries?,
    val chapters: List<Chapter>,
    val canPlay: Boolean,
)
