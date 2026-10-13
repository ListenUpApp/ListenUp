package com.calypsan.listenup.client.playback

import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * The sleep timer's wiring for [PlayerSession]: it feeds the shared [SleepTimerManager] the loaded book and the
 * engine's chapter, runs the fade when the timer fires, and puts full volume back whenever a fade ends early.
 * Touched only on the session's scope, like the session itself.
 */
internal class PlayerSleep(
    private val engine: PlayerEngine,
    private val sleepTimer: SleepTimerManager,
    private val scope: CoroutineScope,
) {
    private var fadeJob: Job? = null

    /** Start following [loadedBookId] and the engine's chapter, and fade when the timer fires. */
    fun start(loadedBookId: Flow<BookId?>) {
        scope.launch { loadedBookId.collect { sleepTimer.onBookChanged(it?.value) } }
        scope.launch {
            engine.currentChapter
                .filterNotNull()
                .distinctUntilChangedBy { it.index }
                .collect { sleepTimer.onChapterChanged(it.index) }
        }
        scope.launch { sleepTimer.sleepEvent.collect { runFade() } }
    }

    /** Start a timer. A duration takes any whole minutes from 1 to [MAX_SLEEP_MINUTES] (the custom stepper, PT8). */
    fun set(mode: SleepTimerMode) {
        val bounded =
            if (mode is SleepTimerMode.Duration) {
                SleepTimerMode.Duration(mode.minutes.coerceIn(1, MAX_SLEEP_MINUTES))
            } else {
                mode
            }
        sleepTimer.setTimer(bounded)
    }

    /** +5 / +10 / +15 (and the notification's +10). */
    fun extend(minutes: Int) = sleepTimer.extendTimer(minutes)

    /** Cancel the timer. During the fade this is "Keep listening": the fade stops and full volume returns (PT7). */
    fun cancel() {
        val wasFading = sleepTimer.state.value is SleepTimerState.FadingOut
        fadeJob?.cancel()
        sleepTimer.cancelTimer()
        if (wasFading) engine.setVolume(1f)
    }

    /** Close book: stop any fade (its `finally` restores the volume) and the timer. */
    fun close() {
        fadeJob?.cancel()
        sleepTimer.cancelTimer()
    }

    /** A different book is loading: a fade meant for the old one must not pause it. */
    fun cancelFade() {
        fadeJob?.cancel()
    }

    /** The timer belongs to [bookId] now (a load names its book before the engine reports it). */
    fun onBookChanged(bookId: BookId) = sleepTimer.onBookChanged(bookId.value)

    private suspend fun runFade() {
        val job =
            scope.launch {
                try {
                    fadeOutAndPause(setVolume = engine::setVolume, pause = engine::pause)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn(e) { "Sleep fade failed; resetting the timer" }
                } finally {
                    // Never leave the timer stuck in FadingOut, nor the output quiet, even when the fade throws or is
                    // cancelled (Close, sign-out, another book): the engine outlives the fade.
                    restoreFullVolume()
                    sleepTimer.onFadeCompleted()
                }
            }
        fadeJob = job
        job.join()
    }

    private fun restoreFullVolume() {
        try {
            engine.setVolume(1f)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Could not restore full volume after the sleep fade" }
        }
    }
}
