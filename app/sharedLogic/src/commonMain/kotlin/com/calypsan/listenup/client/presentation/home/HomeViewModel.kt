package com.calypsan.listenup.client.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.result.onFailure
import com.calypsan.listenup.client.core.error.ErrorMapper
import com.calypsan.listenup.client.core.fallbackTo
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.NetworkMonitor
import com.calypsan.listenup.client.domain.repository.ServerReachability
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.presentation.WhileSubscribedForUi
import com.calypsan.listenup.core.currentHourOfDay
import com.calypsan.listenup.core.error.ErrorBus
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile

private val logger = KotlinLogging.logger {}
private const val CONTINUE_LISTENING_LIMIT = 10

/**
 * UI state for the Home screen: a per-screen sealed hierarchy composed from the user, Continue Listening, shelves, the
 * shell's sync/connectivity state and the slot (spec §4.1) via `combine(...).stateIn(WhileSubscribed)`.
 */
sealed interface HomeUiState {
    /** Before the first emission, and while the loaded book is still being restored (spec §4.1 loading guard). */
    data object Loading : HomeUiState

    /**
     * Home ready.
     *
     * [userName], [timeGreeting] and [continueListening] keep today's screens compiling and retire in Home PR 3 (spec
     * R9). New screens read [firstName], [timeOfDay] and [alsoInProgress].
     */
    data class Ready(
        /** The first name, or empty. Retires in Home PR 3 for [firstName]. */
        val userName: String,
        /** English time-of-day words. Retires in Home PR 3 for [timeOfDay]. */
        val timeGreeting: String,
        /** Every book in progress, the loaded and slot books included. Retires in Home PR 3 for [alsoInProgress]. */
        val continueListening: List<ContinueListeningItem>,
        val myShelves: List<Shelf>,
        /**
         * Whether a sync PASS is running.
         *
         * ⛔ Not "is my library still arriving" — see [isBuildingInitialLibrary] below.
         */
        val isSyncing: Boolean,
        /**
         * Whether the FIRST population of this library is still arriving.
         *
         * Not the same question as [isSyncing], and the difference is load-bearing: the socket is
         * `Connected` for the whole of an initial seed, so [isSyncing] is **false** while thousands
         * of books are still streaming in. Anything that wants to explain a short or empty shelf
         * must read this — see the identical warning on
         * [com.calypsan.listenup.client.presentation.library.LibraryUiState.Loaded].
         */
        val isBuildingInitialLibrary: Boolean,
        val scanProgress: ScanProgressState?,
        /** The part of the day; each platform words the greeting from its catalogs (H2). */
        val timeOfDay: TimeOfDay,
        /** The reader's first name, or null when their profile has none. */
        val firstName: String?,
        /** The one slot under the greeting (spec §2.2), held per §2.1. */
        val slot: HomeSlot,
        /** Continue Listening without the loaded book and the slot's book (spec §2.5). */
        val alsoInProgress: List<ContinueListeningItem>,
        /** Whether this device and the server can be reached (spec §3.1). */
        val connectivity: HomeConnectivity,
        /** Why Continue Listening could not load while the rest of Home did, or null (spec §3.3). */
        val continueListeningError: AppError?,
    ) : HomeUiState {
        val greeting: String
            get() = if (userName.isNotBlank()) "$timeGreeting, $userName" else timeGreeting
        val hasContinueListening: Boolean
            get() = continueListening.isNotEmpty()
        val hasMyShelves: Boolean
            get() = myShelves.isNotEmpty()
        val isLoading: Boolean
            get() = isSyncing
    }

    /** Home could not load at all; [error] says why, as a typed value each platform words. */
    data class Error(
        val error: AppError,
    ) : HomeUiState
}

/**
 * ViewModel for the Home screen (spec §4.1).
 *
 * The slot comes from [HomeSlotPresenter]. This ViewModel adds the decision points only it can see — an explicit
 * [refresh] and a [dismissFinished] — and the two finish-card actions. Play actions are not here: platforms call the
 * player's play entry (spec §5 P-R5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel internal constructor(
    private val homeRepository: HomeRepository,
    userRepository: UserRepository,
    shelfRepository: ShelfRepository,
    private val syncRepository: SyncRepository,
    private val slotPresenter: HomeSlotPresenter,
    serverReachability: ServerReachability,
    networkMonitor: NetworkMonitor,
    private val errorBus: ErrorBus,
    private val currentHour: () -> Int = { currentHourOfDay() },
) : ViewModel() {
    private val userFlow = userRepository.observeCurrentUser()

    private val shelvesFlow: Flow<List<Shelf>> =
        userFlow.flatMapLatest { user ->
            val userId = user?.run { id.value } ?: return@flatMapLatest flowOf(emptyList())
            shelfRepository.observeMyShelves(userId)
        }

    /**
     * Set when this run's Continue Listening observation fell back to an empty row, so [refresh] knows the run needs
     * restarting even though the screen is not in [HomeUiState.Error].
     */
    @Volatile private var continueListeningFailed = false

    private fun continueListeningFlow(): Flow<ContinueListeningRead> =
        homeRepository
            .observeContinueListening(CONTINUE_LISTENING_LIMIT)
            .map { ContinueListeningRead(it, error = null) }
            .fallbackTo { e ->
                logger.error(e) { "Error observing continue listening" }
                continueListeningFailed = true
                ContinueListeningRead(emptyList(), ErrorMapper.map(e))
            }

    // Folded into one upstream because `combine` is typed to five flows.
    private val shellFlow: Flow<HomeShell> =
        combine(
            flow = syncRepository.syncState,
            flow2 = syncRepository.buildingInitialLibrary,
            flow3 = syncRepository.scanProgress,
            flow4 = serverReachability.state,
            flow5 = networkMonitor.onlineFlow,
        ) { sync, building, scan, reachability, online ->
            HomeShell(
                isSyncing = sync is SyncState.Syncing || sync is SyncState.Progress,
                isBuildingInitialLibrary = building,
                scanProgress = scan,
                connectivity = connectivityOf(online, reachability),
            )
        }

    /**
     * Bumped by [refresh] to re-run a failed pipeline.
     *
     * ⛔ Both fallbacks — the screen's Error and Continue Listening's empty row — sit INSIDE the
     * [flatMapLatest], so a failure ends one run rather than the flow the screen is subscribed to.
     */
    private val pipelineRuns = MutableStateFlow(0)

    /** Moves on every explicit refresh and dismissal: the slot's own decision points (spec §2.1 rules 1, 3). */
    private val decisionRuns = MutableStateFlow(0)

    /** Finishes dismissed this visit (book → its finishedAt), hidden before Room has caught up. */
    private val dismissedHere = MutableStateFlow<Map<String, Long>>(emptyMap())

    val state: StateFlow<HomeUiState> =
        pipelineRuns
            .flatMapLatest { homeStatePipeline() }
            .stateIn(
                scope = viewModelScope,
                started = WhileSubscribedForUi,
                initialValue = HomeUiState.Loading,
            )

    private fun homeStatePipeline(): Flow<HomeUiState> =
        channelFlow {
            // One Continue Listening subscription feeds both the slot and the rail.
            val continueListening = continueListeningFlow().stateIn(this)
            val slot =
                slotPresenter.observe(
                    continueListening = continueListening.map { it.items },
                    isBuildingInitialLibrary = syncRepository.buildingInitialLibrary,
                    decisionRuns = decisionRuns,
                    dismissedHere = dismissedHere,
                )
            combine(
                flow = userFlow,
                flow2 = continueListening,
                flow3 = shelvesFlow,
                flow4 = shellFlow,
                flow5 = slot,
            ) { user, read, shelves, shell, slotState ->
                if (slotState == null) {
                    HomeUiState.Loading
                } else {
                    val timeOfDay = timeOfDayAt(currentHour())
                    val firstName = firstNameOf(user?.displayName)
                    HomeUiState.Ready(
                        userName = firstName.orEmpty(),
                        timeGreeting = timeOfDay.englishGreeting(),
                        continueListening = read.items,
                        myShelves = shelves,
                        isSyncing = shell.isSyncing,
                        isBuildingInitialLibrary = shell.isBuildingInitialLibrary,
                        scanProgress = shell.scanProgress,
                        timeOfDay = timeOfDay,
                        firstName = firstName,
                        slot = slotState.slot,
                        alsoInProgress = slotState.alsoInProgress,
                        connectivity = shell.connectivity,
                        continueListeningError = read.error,
                    )
                }
            }.collect { send(it) }
        }.fallbackTo { e ->
            logger.error(e) { "Home state pipeline failed" }
            HomeUiState.Error(ErrorMapper.map(e))
        }

    /**
     * Refresh Home: a decision point for the slot (spec §2.1 rule 1), a full sync, and a re-run of a pipeline that
     * failed. A healthy pipeline is already live on Room and is left alone.
     */
    fun refresh() {
        if (state.value is HomeUiState.Error || continueListeningFailed) {
            continueListeningFailed = false
            pipelineRuns.update { it + 1 }
        }
        decisionRuns.update { it + 1 }
        viewModelScope.launch {
            logger.debug { "Refresh: triggering sync to pull latest progress" }
            syncRepository
                .sync()
                .onFailure { logger.warn { "Home refresh sync failed: ${it.message}" } }
        }
    }

    /**
     * Dismiss the just-finished card for [bookId] on every device (spec §2.3, S2). The card goes at once (a decision
     * point, §2.1 rule 3); if the dismissal cannot be saved, it comes back and the error bus says why.
     */
    fun dismissFinished(bookId: String) {
        val ready = state.value as? HomeUiState.Ready ?: return
        val card = (ready.slot as? HomeSlot.JustFinished)?.card ?: return
        if (card.book.bookId != bookId) return
        dismissedHere.update { it + (bookId to card.finishedAtMs) }
        decisionRuns.update { it + 1 }
        viewModelScope.launch {
            slotPresenter.dismissFinished(bookId).onFailure { error ->
                errorBus.emit(error)
                dismissedHere.update { it - bookId }
                decisionRuns.update { it + 1 }
            }
        }
    }

    /** Rate [bookId] from its just-finished card, on Now Playing's scale: [halfStars] 2..10. */
    fun rateFinished(
        bookId: String,
        halfStars: Int,
    ) {
        viewModelScope.launch {
            slotPresenter.rateFinished(bookId, halfStars).onFailure(errorBus::emit)
        }
    }
}

/** One Continue Listening read: the items, and why they are empty if the read failed. */
private data class ContinueListeningRead(
    val items: List<ContinueListeningItem>,
    val error: AppError?,
)

/** The shell's sync and connectivity state, folded for `combine`. */
private data class HomeShell(
    val isSyncing: Boolean,
    val isBuildingInitialLibrary: Boolean,
    val scanProgress: ScanProgressState?,
    val connectivity: HomeConnectivity,
)

// English words for Ready.timeGreeting only; retires with it in Home PR 3 (spec R9).
private fun TimeOfDay.englishGreeting(): String =
    when (this) {
        TimeOfDay.Morning -> "Good morning"
        TimeOfDay.Afternoon -> "Good afternoon"
        TimeOfDay.Evening -> "Good evening"
        TimeOfDay.Night -> "Good night"
    }
