package com.calypsan.listenup.client.presentation.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.calypsan.listenup.client.domain.model.BookEvent
import com.calypsan.listenup.client.domain.model.ReaderInfo
import com.calypsan.listenup.client.domain.model.SessionSummary
import com.calypsan.listenup.client.domain.repository.EventStreamRepository
import com.calypsan.listenup.client.domain.repository.SessionRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

/**
 * ViewModel for the Book Readers screen.
 *
 * Displays who else has read or is reading a book, along with
 * the current user's own reading history for that book.
 *
 * Uses offline-first architecture:
 * - Observes local Room cache for instant display
 * - Repository triggers background API refresh automatically
 * - SSE events update the cache for real-time updates
 */
@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class BookReadersViewModel(
    private val sessionRepository: SessionRepository,
    private val eventStreamRepository: EventStreamRepository,
    private val userRepository: UserRepository,
) : ViewModel() {
    /**
     * The currently requested book id. Writing to this flow is the single entry
     * point for switching books — both the state stream and the SSE-trigger
     * side-effect subscribe via `flatMapLatest`, so book-switch cancellation is
     * automatic.
     */
    private val currentBookId = MutableStateFlow<String?>(null)

    val state: StateFlow<BookReadersUiState> =
        currentBookId
            .filterNotNull()
            .flatMapLatest { id ->
                sessionRepository
                    .observeBookReaders(id)
                    .map { result ->
                        BookReadersUiState.Ready(
                            yourSessions = result.yourSessions,
                            currentUserReaderInfo = buildCurrentUserReaderInfo(result.yourSessions),
                            otherReaders = result.otherReaders,
                            totalReaders = result.totalReaders,
                            totalCompletions = result.totalCompletions,
                        ) as BookReadersUiState
                    }.catch { e ->
                        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                        logger.error(e) { "Error observing book readers for $id" }
                        emit(BookReadersUiState.Error(e.message ?: "Failed to load readers"))
                    }.onStart { emit(BookReadersUiState.Loading) }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS),
                initialValue = BookReadersUiState.Loading,
            )

    init {
        // SSE-trigger side-effect: when a reading session updates for the current
        // book, debounce-then-refresh. flatMapLatest cancels the previous book's
        // SSE subscription on switch, so no manual job management.
        //
        // Intentionally runs for the full viewModelScope lifetime (not gated by
        // state's WhileSubscribed(5s)): refresh writes to Room so cache stays warm
        // while the screen is briefly unmounted. Matches pre-rewrite behavior.
        viewModelScope.launch {
            currentBookId
                .filterNotNull()
                .flatMapLatest { id ->
                    eventStreamRepository.bookEvents
                        .mapNotNull { event ->
                            if (event is BookEvent.ReadingSessionUpdated && event.bookId == id) id else null
                        }.debounce(2.seconds)
                }.collect { id ->
                    try {
                        logger.debug { "SSE: Reading session updated for book $id, refreshing cache (debounced)" }
                        sessionRepository.refreshBookReaders(id)
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(e) { "SSE-triggered refresh failed for $id" }
                    }
                }
        }
    }

    /**
     * Start observing readers for a specific book. Idempotent: repeated calls with
     * the same book-id are no-ops because `MutableStateFlow` doesn't re-emit equal
     * values.
     *
     * @param bookId Book ID to observe readers for
     */
    fun observeReaders(bookId: String) {
        currentBookId.value = bookId
    }

    /**
     * Manually refresh the readers list — bypasses the debounce.
     *
     * @param bookId Book ID to refresh readers for
     */
    fun refresh(bookId: String) {
        viewModelScope.launch {
            try {
                sessionRepository.refreshBookReaders(bookId)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Manual refresh failed for $bookId" }
            }
        }
    }

    /**
     * Build a ReaderInfo for the current user from their sessions.
     *
     * Combines session data with current user profile to create a unified
     * reader representation that can be displayed like any other reader.
     */
    private suspend fun buildCurrentUserReaderInfo(sessions: List<SessionSummary>): ReaderInfo? {
        if (sessions.isEmpty()) return null

        val user = userRepository.getCurrentUser() ?: return null

        // Find the most recent session
        val mostRecent = sessions.maxByOrNull { it.startedAt } ?: return null
        val completionCount = sessions.count { it.isCompleted }
        val isCurrentlyReading = mostRecent.finishedAt == null

        // Compute last activity (most recent of startedAt or finishedAt)
        val lastActivity = mostRecent.finishedAt ?: mostRecent.startedAt

        return ReaderInfo(
            userId = user.id.value,
            displayName = user.displayName,
            avatarType = user.avatarType,
            avatarValue = user.avatarValue,
            avatarColor = user.avatarColor,
            isCurrentlyReading = isCurrentlyReading,
            currentProgress = 0.0, // Progress would need to come from PlaybackState
            startedAt = mostRecent.startedAt,
            finishedAt = mostRecent.finishedAt,
            lastActivityAt = lastActivity,
            completionCount = completionCount,
            isCurrentUser = true,
        )
    }
}

/**
 * UI state for the Book Readers screen.
 */
sealed interface BookReadersUiState {
    /** Initial state before the repository flow emits. */
    data object Loading : BookReadersUiState

    /** Readers loaded successfully. */
    data class Ready(
        val yourSessions: List<SessionSummary> = emptyList(),
        val currentUserReaderInfo: ReaderInfo? = null,
        val otherReaders: List<ReaderInfo> = emptyList(),
        val totalReaders: Int = 0,
        val totalCompletions: Int = 0,
    ) : BookReadersUiState {
        /**
         * True if there are no sessions or readers to display.
         */
        val isEmpty: Boolean
            get() = yourSessions.isEmpty() && otherReaders.isEmpty()

        /**
         * True if the current user has any reading history for this book.
         */
        val hasYourHistory: Boolean
            get() = currentUserReaderInfo != null

        /**
         * True if there are other readers besides the current user.
         */
        val hasOtherReaders: Boolean
            get() = otherReaders.isNotEmpty()

        /**
         * All readers including current user, sorted by last activity.
         */
        val allReaders: List<ReaderInfo>
            get() =
                buildList {
                    currentUserReaderInfo?.let { add(it) }
                    addAll(otherReaders)
                }.sortedByDescending { it.lastActivityAt }

        /**
         * Number of other readers currently reading (not completed).
         */
        val currentlyReadingCount: Int
            get() = otherReaders.count { it.isCurrentlyReading }
    }

    /** Load or observation failure. */
    data class Error(
        val message: String,
    ) : BookReadersUiState
}
