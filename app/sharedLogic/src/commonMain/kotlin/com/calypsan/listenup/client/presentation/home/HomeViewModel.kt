package com.calypsan.listenup.client.presentation.home

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.api.result.onFailure
import com.calypsan.listenup.core.currentHourOfDay
import com.calypsan.listenup.client.core.fallbackTo
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.client.domain.model.Shelf
import com.calypsan.listenup.client.domain.model.SyncState
import com.calypsan.listenup.client.domain.repository.HomeRepository
import com.calypsan.listenup.client.domain.repository.ShelfRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}
private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
private const val CONTINUE_LISTENING_LIMIT = 10

/**
 * UI state for the Home screen.
 *
 * Sealed hierarchy composed from multiple upstreams (user, continue listening,
 * shelves, sync state, scan progress) via `combine(...).stateIn(WhileSubscribed)`.
 */
sealed interface HomeUiState {
    /** Pre-first-emission placeholder. */
    data object Loading : HomeUiState

    /**
     * Home ready. `userName`, `continueListening`, and `myShelves` all have
     * sensible defaults even before their upstreams produce real data.
     */
    data class Ready(
        val userName: String,
        val timeGreeting: String,
        val continueListening: List<ContinueListeningItem>,
        val myShelves: List<Shelf>,
        val isSyncing: Boolean,
        val scanProgress: ScanProgressState?,
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

    /** Catastrophic load failure (rarely hit). */
    data class Error(
        val message: String,
    ) : HomeUiState
}

/**
 * ViewModel for the Home screen.
 *
 * Composes user, continue listening, shelves, sync state, and scan progress
 * into a single sealed [HomeUiState] via `combine(...).stateIn(WhileSubscribed)`.
 *
 * Transient failures (e.g. continue-listening observation errors) surface via
 * [snackbarMessages] so Ready state is not wiped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val homeRepository: HomeRepository,
    private val userRepository: UserRepository,
    private val shelfRepository: ShelfRepository,
    private val syncRepository: SyncRepository,
    private val currentHour: () -> Int = { currentHourOfDay() },
) : ViewModel() {
    private var closed = false

    override fun onCleared() {
        super.onCleared()
        close()
    }

    /**
     * Cancels this ViewModel's coroutines. Idempotent. Android reaches it via [onCleared] when the
     * `ViewModelStore` clears the entry; iOS has no store, so the screen's wrapper calls it from its
     * `isolated deinit` (#1192) — else viewModelScope streams/one-shots orphan when the screen goes.
     */
    fun close() {
        if (closed) return
        closed = true
        viewModelScope.cancel()
    }

    private val snackbarChannel = Channel<String>(Channel.BUFFERED)
    val snackbarMessages: Flow<String> = snackbarChannel.receiveAsFlow()

    private val userFlow = userRepository.observeCurrentUser()

    private val shelvesFlow: Flow<List<Shelf>> =
        userFlow.flatMapLatest { user ->
            val userId = user?.id?.value ?: return@flatMapLatest flowOf(emptyList())
            shelfRepository.observeMyShelves(userId)
        }

    private val continueListeningFlow: Flow<List<ContinueListeningItem>> =
        homeRepository
            .observeContinueListening(CONTINUE_LISTENING_LIMIT)
            .fallbackTo { e ->
                logger.error(e) { "Error observing continue listening" }
                snackbarChannel.trySend("Failed to load continue listening")
                emptyList()
            }

    val state: StateFlow<HomeUiState> =
        combine(
            userFlow,
            continueListeningFlow,
            shelvesFlow,
            syncRepository.syncState,
            syncRepository.scanProgress,
        ) { user, cl, shelves, sync, scan ->
            val ready: HomeUiState =
                HomeUiState.Ready(
                    userName = extractFirstName(user?.displayName).orEmpty(),
                    timeGreeting = computeTimeGreeting(),
                    continueListening = cl,
                    myShelves = shelves,
                    isSyncing = sync is SyncState.Syncing || sync is SyncState.Progress,
                    scanProgress = scan,
                )
            ready
        }.fallbackTo { e ->
            logger.error(e) { "Home state pipeline failed" }
            HomeUiState.Error("Failed to load home screen")
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
            initialValue = HomeUiState.Loading,
        )

    /**
     * Refresh home screen data.
     *
     * Triggers a full sync with the server, which pulls updated playback progress
     * (including isFinished state) into local Room. The continue listening and
     * shelves Flows then emit automatically with the new data.
     */
    fun refresh() {
        viewModelScope.launch {
            logger.debug { "Refresh: triggering sync to pull latest progress" }
            syncRepository
                .sync()
                .onFailure { logger.warn { "Home refresh sync failed: ${it.message}" } }
        }
    }

    private fun computeTimeGreeting(): String =
        when (currentHour()) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..20 -> "Good evening"
            else -> "Good night"
        }

    private fun extractFirstName(displayName: String?): String? {
        if (displayName.isNullOrBlank()) return null
        return displayName.split(" ").firstOrNull()?.takeIf { it.isNotBlank() }
    }
}
