package com.calypsan.listenup.client.presentation.match

import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.MetadataError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.core.error.ErrorBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val COUNTDOWN_TICK_MS = 1_000L

/** The Find phase shared by book and person match sessions. */
internal sealed interface MatchFindPhase<out Loaded, out Meta> {
    /** A Find is running; the previous loaded results stay on screen. */
    data class Searching<out Loaded>(
        val previous: Loaded?,
    ) : MatchFindPhase<Loaded, Nothing>

    /** Find returned at least one candidate. */
    data class LoadedPhase<out Loaded>(
        val loaded: Loaded,
    ) : MatchFindPhase<Loaded, Nothing>

    /** Every source answered empty with no failures (used by person match). */
    data object Empty : MatchFindPhase<Nothing, Nothing>

    /** Find returned no candidates for [failure]; [meta] holds any domain-specific context. */
    data class Failed<out Meta>(
        val failure: FindFailure,
        val meta: Meta,
    ) : MatchFindPhase<Nothing, Meta>
}

/** The Review + Apply phase shared by book and person match sessions. */
internal sealed interface MatchReviewPhase<out Candidate, out Review> {
    data object None : MatchReviewPhase<Nothing, Nothing>

    /** The Review for [candidate] is loading. */
    data class Loading<out Candidate>(
        val candidate: Candidate,
    ) : MatchReviewPhase<Candidate, Nothing>

    /** The Review is loaded; Apply may be running. */
    data class Ready<out Candidate, out Review>(
        val candidate: Candidate,
        val review: Review,
        val applying: Boolean,
        val applyError: AppError?,
    ) : MatchReviewPhase<Candidate, Review>

    /** The Review failed to load. */
    data class Failed<out Candidate>(
        val candidate: Candidate,
        val error: AppError,
    ) : MatchReviewPhase<Candidate, Nothing>
}

/** Candidate projection extracted from a loaded Find phase for picking and two-pane auto-select. */
internal data class MatchLoadedCandidates<out Candidate>(
    val all: List<Candidate>,
    val bestStrong: Candidate?,
)

/** Mutable session state managed by [MatchSessionController]. */
internal data class MatchCoreState<Key, Candidate, Loaded, Meta, Review, Choices>(
    val find: MatchFindPhase<Loaded, Meta> = MatchFindPhase.Searching(previous = null),
    val lastLoaded: Loaded? = null,
    val pickedKey: Key? = null,
    val review: MatchReviewPhase<Candidate, Review> = MatchReviewPhase.None,
    val choices: Map<Key, Choices> = emptyMap(),
    val twoPane: Boolean = false,
) {
    /** Returns the saved choices for [key], or [defaultChoices] when untouched. */
    fun choicesFor(
        key: Key,
        defaultChoices: () -> Choices,
    ): Choices = choices[key] ?: defaultChoices()
}

/**
 * Shared Find -> Rate-Limit Countdown -> Review -> Apply state machine for [BookMatchViewModel] and
 * [PersonMatchViewModel].
 */
internal class MatchSessionController<Key, Candidate, Loaded, Meta, Review, Choices>(
    private val scope: CoroutineScope,
    private val errorBus: ErrorBus,
    private val defaultChoices: () -> Choices,
    private val keyOf: (Candidate) -> Key,
    private val candidatesOf: (Loaded) -> MatchLoadedCandidates<Candidate>,
    private val loadReview: suspend (Candidate) -> AppResult<Review>,
) {
    private val mutableState =
        MutableStateFlow(MatchCoreState<Key, Candidate, Loaded, Meta, Review, Choices>())

    /** Observable state for combination with domain-specific Room flows. */
    val state: StateFlow<MatchCoreState<Key, Candidate, Loaded, Meta, Review, Choices>> = mutableState

    private var findJob: Job? = null
    private var countdownJob: Job? = null
    private var reviewJob: Job? = null

    /** True while a rate-limit cooldown still has remaining seconds. */
    fun isRateLimitCoolingDown(): Boolean {
        val failed = state.value.find as? MatchFindPhase.Failed<*>
        val limited = failed?.failure as? FindFailure.RateLimited
        return limited != null && limited.secondsRemaining > 0
    }

    /** Cancels any prior Find/countdown, transitions to Searching preserving previous results, and runs [fetch]. */
    fun startFind(
        failedMeta: Meta,
        fetch: suspend () -> AppResult<MatchFindPhase<Loaded, Meta>>,
    ) {
        findJob?.cancel()
        countdownJob?.cancel()
        mutableState.update { current ->
            val prev = (current.find as? MatchFindPhase.LoadedPhase)?.loaded ?: current.lastLoaded
            current.copy(find = MatchFindPhase.Searching(previous = prev))
        }
        findJob =
            scope.launch {
                when (val result = fetch()) {
                    is AppResult.Success -> {
                        handleFindPhase(result.data)
                    }

                    is AppResult.Failure -> {
                        errorBus.emit(result.error)
                        mutableState.update {
                            it.copy(find = MatchFindPhase.Failed(findFailureFor(result.error), failedMeta))
                        }
                    }
                }
            }
    }

    /** Opens Review for the candidate identified by [key] in the current loaded results. */
    fun pick(key: Key) {
        val loaded = state.value.find as? MatchFindPhase.LoadedPhase ?: return
        val candidate = candidatesOf(loaded.loaded).all.firstOrNull { keyOf(it) == key } ?: return
        startPick(candidate)
    }

    /** Cancels any in-flight review load and clears the active review back to None. */
    fun backToResults() {
        reviewJob?.cancel()
        mutableState.update { it.copy(review = MatchReviewPhase.None) }
    }

    /** Updates two-pane mode and auto-picks the best strong candidate when enabling two-pane. */
    fun useTwoPane(enabled: Boolean) {
        mutableState.update { it.copy(twoPane = enabled) }
        if (enabled) autoPickBest()
    }

    /** Transforms saved review choices for the currently ready candidate unless Apply is in flight. */
    fun updateChoices(transform: (Choices, Review) -> Choices) {
        mutableState.update { current ->
            val ready = current.review as? MatchReviewPhase.Ready ?: return@update current
            if (ready.applying) return@update current
            val key = keyOf(ready.candidate)
            val updated = transform(current.choicesFor(key, defaultChoices), ready.review)
            current.copy(choices = current.choices + (key to updated))
        }
    }

    /** Submits Apply for the ready candidate, reloading on [MetadataError.ReviewOutdated]. */
    fun <Receipt> apply(
        submit: suspend (Choices, Review) -> AppResult<Receipt>,
        onApplied: suspend (Receipt) -> Unit,
        onReviewReloaded: suspend () -> Unit,
    ) {
        val current = state.value
        val ready = current.review as? MatchReviewPhase.Ready ?: return
        if (ready.applying) return
        val choices = current.choicesFor(keyOf(ready.candidate), defaultChoices)
        updateReady { it.copy(applying = true, applyError = null) }
        scope.launch {
            when (val result = submit(choices, ready.review)) {
                is AppResult.Success -> {
                    updateReady { it.copy(applying = false) }
                    onApplied(result.data)
                }

                is AppResult.Failure -> {
                    if (result.error is MetadataError.ReviewOutdated) {
                        reloadAfterOutdated(ready.candidate, onReviewReloaded)
                    } else {
                        errorBus.emit(result.error)
                        updateReady { it.copy(applying = false, applyError = result.error) }
                    }
                }
            }
        }
    }

    private fun handleFindPhase(phase: MatchFindPhase<Loaded, Meta>) {
        when (phase) {
            is MatchFindPhase.LoadedPhase -> {
                mutableState.update { it.copy(find = phase, lastLoaded = phase.loaded) }
                autoPickBest()
            }

            is MatchFindPhase.Failed -> {
                mutableState.update { it.copy(find = phase) }
                val limited = phase.failure as? FindFailure.RateLimited
                if (limited != null) countDown(limited)
            }

            MatchFindPhase.Empty,
            is MatchFindPhase.Searching,
            -> {
                mutableState.update { it.copy(find = phase) }
            }
        }
    }

    private fun countDown(start: FindFailure.RateLimited) {
        countdownJob?.cancel()
        countdownJob =
            scope.launch {
                var remaining = start.secondsRemaining
                while (remaining > 0) {
                    delay(COUNTDOWN_TICK_MS)
                    remaining--
                    val left = remaining
                    mutableState.update { current ->
                        val failed = current.find as? MatchFindPhase.Failed ?: return@update current
                        val limited = failed.failure as? FindFailure.RateLimited ?: return@update current
                        current.copy(find = failed.copy(failure = limited.copy(secondsRemaining = left)))
                    }
                }
            }
    }

    private fun autoPickBest() {
        val current = state.value
        if (!current.twoPane || current.review != MatchReviewPhase.None || current.pickedKey != null) return
        val loaded = current.find as? MatchFindPhase.LoadedPhase ?: return
        val best = candidatesOf(loaded.loaded).bestStrong ?: return
        startPick(best)
    }

    private fun startPick(candidate: Candidate) {
        val key = keyOf(candidate)
        mutableState.update { it.copy(pickedKey = key, review = MatchReviewPhase.Loading(candidate)) }
        fetchReview(candidate)
    }

    private fun fetchReview(candidate: Candidate) {
        reviewJob?.cancel()
        reviewJob =
            scope.launch {
                when (val result = loadReview(candidate)) {
                    is AppResult.Success -> {
                        mutableState.update { current ->
                            current.copy(
                                review =
                                    MatchReviewPhase.Ready(
                                        candidate = candidate,
                                        review = result.data,
                                        applying = false,
                                        applyError = null,
                                    ),
                            )
                        }
                    }

                    is AppResult.Failure -> {
                        errorBus.emit(result.error)
                        mutableState.update { it.copy(review = MatchReviewPhase.Failed(candidate, result.error)) }
                    }
                }
            }
    }

    private suspend fun reloadAfterOutdated(
        candidate: Candidate,
        onReviewReloaded: suspend () -> Unit,
    ) {
        when (val reloaded = loadReview(candidate)) {
            is AppResult.Success -> {
                mutableState.update { current ->
                    current.copy(
                        review =
                            MatchReviewPhase.Ready(
                                candidate = candidate,
                                review = reloaded.data,
                                applying = false,
                                applyError = null,
                            ),
                    )
                }
                onReviewReloaded()
            }

            is AppResult.Failure -> {
                errorBus.emit(reloaded.error)
                mutableState.update { it.copy(review = MatchReviewPhase.Failed(candidate, reloaded.error)) }
            }
        }
    }

    private fun updateReady(
        transform: (MatchReviewPhase.Ready<Candidate, Review>) -> MatchReviewPhase.Ready<Candidate, Review>,
    ) {
        mutableState.update { current ->
            val ready = current.review as? MatchReviewPhase.Ready ?: return@update current
            current.copy(review = transform(ready))
        }
    }
}
