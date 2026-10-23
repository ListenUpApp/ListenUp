package com.calypsan.listenup.client.playback

import com.calypsan.listenup.client.presentation.player.SleepTimerChoices
import com.calypsan.listenup.core.BookId
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

/**
 * The sleep timer's wiring for [PlayerSession]: it feeds the shared [SleepTimerManager] the loaded book and the
 * engine's chapter, runs the fade when the timer fires, and puts full volume back whenever a fade ends early.
 * Touched only on the session's scope, like the session itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlayerSleep(
    private val engine: PlayerEngine,
    private val sleepTimer: SleepTimerManager,
    private val scope: CoroutineScope,
) {
    private var fadeJob: Job? = null

    /**
     * Start following [loadedBookId] and the engine's chapter, and fade when the timer fires.
     *
     * One chain, so a book change (Close and reopen included) always reaches the timer before that book's chapter,
     * and the chapter is reported afresh for every book: the timer forgets its end-of-chapter baseline on a book
     * change, and a chapter swallowed as "unchanged" would leave it to be relearned at the next boundary, firing a
     * chapter late. Only the chapter of the book the engine holds is reported, never the previous book's.
     */
    fun start(loadedBookId: Flow<BookId?>) {
        scope.launch {
            loadedBookId
                .onEach { sleepTimer.onBookChanged(it?.value) }
                .flatMapLatest { bookId -> chapterIndexOf(bookId) }
                .collect { sleepTimer.onChapterChanged(it) }
        }
        scope.launch { sleepTimer.sleepEvent.collect { runFade() } }
    }

    /** [bookId]'s chapter index while the engine holds it; re-reported each time the engine takes the book up again. */
    private fun chapterIndexOf(bookId: BookId?): Flow<Int> =
        if (bookId == null) {
            emptyFlow()
        } else {
            combine(engine.loadedBookId, engine.currentChapter) { held, chapter ->
                if (held == bookId) chapter?.index else null
            }.distinctUntilChanged()
                .filterNotNull()
        }

    /** Start a timer. A duration takes any whole minutes within [SleepTimerChoices]' custom bounds (PT8). */
    fun set(mode: SleepTimerMode) {
        val bounded =
            if (mode is SleepTimerMode.Duration) {
                SleepTimerMode.Duration(mode.minutes.coerceIn(SleepTimerChoices.CUSTOM_MIN_MINUTES, SleepTimerChoices.CUSTOM_MAX_MINUTES))
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
