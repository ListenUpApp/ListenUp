@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.playback

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private val logger = KotlinLogging.logger {}

/**
 * Manages the sleep timer for audiobook playback.
 *
 * Features:
 * - Duration-based timers (15, 30, 45, 60, 120 minutes)
 * - End of chapter mode (pauses when chapter ends)
 * - Extend timer while active
 *
 * The actual fade-out and pause is performed by the consumer (NowPlayingViewModel)
 * which has access to MediaController.
 */
class SleepTimerManager(
    private val scope: CoroutineScope,
    /**
     * Wall-clock read seam. Defaults to the system clock; tests inject a virtual clock (e.g. the
     * coroutines-test scheduler) so the fire-after-duration behaviour can be pinned deterministically.
     */
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    val state: StateFlow<SleepTimerState>
        field = MutableStateFlow<SleepTimerState>(SleepTimerState.Inactive)

    // Event emitted when timer fires - consumer performs fade and pause
    private val _sleepEvent = Channel<Unit>(Channel.BUFFERED)
    val sleepEvent: Flow<Unit> = _sleepEvent.receiveAsFlow()

    private var timerJob: Job? = null
    private var endOfChapterJob: Job? = null

    // Current chapter index for end-of-chapter mode
    private var lastKnownChapterIndex: Int = -1

    companion object {
        private const val TICK_INTERVAL_MS = 1000L
        private const val MS_PER_MINUTE = 60_000L
    }

    /**
     * Start a sleep timer with the specified mode.
     */
    fun setTimer(mode: SleepTimerMode) {
        logger.info { "Setting sleep timer: $mode" }
        cancelTimer()

        when (mode) {
            is SleepTimerMode.Duration -> startDurationTimer(mode.minutes)
            is SleepTimerMode.EndOfChapter -> startEndOfChapterTimer()
        }
    }

    /**
     * Cancel the active timer.
     */
    fun cancelTimer() {
        logger.info { "Canceling sleep timer" }
        timerJob?.cancel()
        timerJob = null
        endOfChapterJob?.cancel()
        endOfChapterJob = null
        lastKnownChapterIndex = -1
        state.value = SleepTimerState.Inactive
    }

    /**
     * Add time to an active duration timer.
     */
    fun extendTimer(additionalMinutes: Int) {
        val current = state.value
        if (current is SleepTimerState.Active && current.mode is SleepTimerMode.Duration) {
            val additionalMs = additionalMinutes * MS_PER_MINUTE
            val newRemaining = current.remainingMs + additionalMs
            val newTotal = current.totalMs + additionalMs

            logger.info {
                "Extending timer by $additionalMinutes min, new remaining: ${newRemaining / MS_PER_MINUTE} min"
            }

            state.value =
                current.copy(
                    remainingMs = newRemaining,
                    totalMs = newTotal,
                )
        }
    }

    /**
     * Called by NowPlayingViewModel when chapter changes.
     * Used for end-of-chapter mode detection.
     */
    @Suppress("CollapsibleIfStatements") // Nested if improves readability here
    fun onChapterChanged(newChapterIndex: Int) {
        val current = state.value
        if (current is SleepTimerState.Active && current.mode is SleepTimerMode.EndOfChapter) {
            // Chapter moved forward - previous chapter ended naturally
            if (lastKnownChapterIndex >= 0 && newChapterIndex > lastKnownChapterIndex) {
                logger.info { "Chapter ended ($lastKnownChapterIndex -> $newChapterIndex), triggering sleep" }
                triggerSleep()
            }
        }
        lastKnownChapterIndex = newChapterIndex
    }

    /**
     * Called by NowPlayingViewModel after fade completes.
     * Resets state to Inactive.
     */
    fun onFadeCompleted() {
        state.value = SleepTimerState.Inactive
        timerJob = null
        endOfChapterJob = null
        lastKnownChapterIndex = -1
        logger.info { "Sleep timer completed" }
    }

    private fun startDurationTimer(minutes: Int) {
        val totalMs = minutes * MS_PER_MINUTE
        val startedAt = nowMillis()

        state.value =
            SleepTimerState.Active(
                mode = SleepTimerMode.Duration(minutes),
                remainingMs = totalMs,
                totalMs = totalMs,
                startedAt = startedAt,
            )

        timerJob =
            scope.launch {
                logger.debug { "Starting $minutes minute timer" }

                while (isActive) {
                    delay(TICK_INTERVAL_MS)

                    val current = state.value
                    if (current !is SleepTimerState.Active) break

                    val elapsed = nowMillis() - current.startedAt
                    val remaining = (current.totalMs - elapsed).coerceAtLeast(0)

                    state.value = current.copy(remainingMs = remaining)

                    if (remaining <= 0) {
                        logger.info { "Duration timer completed" }
                        triggerSleep()
                        break
                    }
                }
            }
    }

    private fun startEndOfChapterTimer() {
        state.value =
            SleepTimerState.Active(
                mode = SleepTimerMode.EndOfChapter,
                remainingMs = 0,
                totalMs = 0,
                startedAt = nowMillis(),
            )
        logger.debug { "Started end-of-chapter timer, waiting for chapter change" }
    }

    private fun triggerSleep() {
        state.value = SleepTimerState.FadingOut
        _sleepEvent.trySend(Unit)
    }
}
