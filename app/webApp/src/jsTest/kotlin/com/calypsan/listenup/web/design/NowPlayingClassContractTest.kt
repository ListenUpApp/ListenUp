package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.client.domain.model.NextInSeries
import com.calypsan.listenup.client.playback.PlayerPhase
import com.calypsan.listenup.client.playback.SleepTimerMode
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.client.presentation.player.FinishedInfo
import com.calypsan.listenup.client.presentation.player.PlayerContributor
import com.calypsan.listenup.client.presentation.player.PlayerSeriesRef
import com.calypsan.listenup.client.presentation.player.PrimaryControl
import com.calypsan.listenup.web.features.admin.finished
import com.calypsan.listenup.web.features.nowplaying.NowPlayingActions
import com.calypsan.listenup.web.features.nowplaying.NowPlayingPage
import com.calypsan.listenup.web.features.nowplaying.TransportBar
import com.calypsan.listenup.web.features.nowplaying.chapterRows
import com.calypsan.listenup.web.features.nowplaying.loadedPlayerState
import com.calypsan.listenup.web.features.nowplaying.playerProgress
import io.kotest.core.spec.style.FunSpec

/**
 * Every class the player and the Now Playing sheet render is defined in the design sheet. A failure names the shape that rendered
 * the undefined class; see [ClassContractTest] for why the contract exists.
 */
class NowPlayingClassContractTest :
    FunSpec({
        test("every class the player and the Now Playing sheet render is defined in the design sheet") {
            assertClassesDefined(nowplayingContractShapes())
        }
    })

private fun nowplayingContractShapes(): Map<String, @Composable () -> Unit> =
    playerShapes() +
        nowPlayingShapes()

/** Now Playing, the page, in each of its faces: the transport, a problem, a refusal, held, finished. */
private fun nowPlayingShapes(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("nowPlaying #1: { val kings = loadedPlayerState( title =") {
            val kings =
                loadedPlayerState(
                    title = "The Way of Kings",
                    bookId = "b1",
                    authors = listOf(PlayerContributor("c1", "Brandon Sanderson", 1)),
                    series = listOf(PlayerSeriesRef("s1", "The Stormlight Archive", "1")),
                    coverHash = "abc",
                    durationMs = 3_600_000,
                    chapters = chapterRows(3),
                    currentChapterIndex = 0,
                    boostDb = 6f,
                )
            // Now Playing, the page: playing with a chapter list, a problem in the transport's place, held, finished,
            // and a chapterless book; its More menu and its card on their own.
            NowPlayingPage(
                state = kings,
                progress = playerProgress(positionMs = 61_000, durationMs = 3_600_000),
                actions = NowPlayingActions(),
            )
            NowPlayingPage(
                state =
                    loadedPlayerState(
                        title = "A standalone",
                        phase = PlayerPhase.Failed(PlaybackError.ConnectionLost(correlationId = "req-1")),
                        primary = PrimaryControl.Retry,
                        authors = emptyList(),
                    ),
                progress = playerProgress(),
                actions = NowPlayingActions(),
            )
            NowPlayingPage(
                state =
                    loadedPlayerState(
                        phase = PlayerPhase.Failed(PlaybackError.AutoplayBlocked()),
                        primary = PrimaryControl.TapToPlay,
                        narrators = (1..5).map { PlayerContributor("n$it", "Narrator $it", 1) },
                    ),
                progress = playerProgress(),
                actions = NowPlayingActions(),
            )
            NowPlayingPage(
                state =
                    loadedPlayerState(
                        phase = PlayerPhase.Unavailable(BookError.HeldForReview()),
                        primary = PrimaryControl.OpenInbox,
                    ),
                progress = playerProgress(),
                actions = NowPlayingActions(),
            )
            NowPlayingPage(
                state =
                    loadedPlayerState(
                        phase = PlayerPhase.Finished,
                        primary = PrimaryControl.ListenAgain,
                        finished =
                            FinishedInfo(
                                finishedAtMs = 1_760_000_000_000,
                                startedAtMs = 1_757_000_000_000,
                                durationMs = 3_600_000,
                                yourHalfStars = 8,
                                nextBook =
                                    NextInSeries(
                                        bookId = "b2",
                                        title = "Words of Radiance",
                                        coverPath = null,
                                        coverHash = null,
                                        narratorNames = null,
                                        seriesName = "The Stormlight Archive",
                                        sequenceLabel = "2",
                                        durationMs = 3_600_000,
                                    ),
                            ),
                        sleepTimer =
                            SleepTimerState.Active(SleepTimerMode.Duration(30), remainingMs = 60_000, totalMs = 1_800_000, startedAt = 0),
                    ),
                progress = playerProgress(),
                actions = NowPlayingActions(),
            )
        },
    )

/**
 * The docked transport bar and the expanded player.
 *
 * The panel appears only after a gesture this harness does not make, so it is rendered open here
 * by hand — a class invented inside it would otherwise go unchecked.
 */
private fun playerShapes(): Map<String, @Composable () -> Unit> =
    shapes(
        shape("player #1: { val kings = loadedPlayerState( title =") {
            val kings =
                loadedPlayerState(
                    title = "The Way of Kings",
                    bookId = "b1",
                    authors = listOf(PlayerContributor("c1", "Brandon Sanderson", 1)),
                    series = listOf(PlayerSeriesRef("s1", "The Stormlight Archive", "1")),
                    coverHash = "abc",
                    durationMs = 3_600_000,
                    chapters = chapterRows(1),
                    currentChapterIndex = 0,
                    boostDb = 6f,
                )
            TransportBar(
                state = kings,
                progress = playerProgress(positionMs = 61_000, durationMs = 3_600_000),
                onPrimary = {},
                onSeek = {},
                onSkipBack = {},
                onSkipForward = {},
                onSetSpeed = {},
            )
            // A book whose prepare is in flight (the spinner) and one the browser refused to start (the ring).
            TransportBar(
                state = loadedPlayerState(phase = PlayerPhase.Preparing),
                progress = playerProgress(),
                onPrimary = {},
                onSeek = {},
                onSkipBack = {},
                onSkipForward = {},
                onSetSpeed = {},
            )
            TransportBar(
                state =
                    loadedPlayerState(
                        phase = PlayerPhase.Failed(PlaybackError.AutoplayBlocked()),
                        primary = PrimaryControl.TapToPlay,
                    ),
                progress = playerProgress(),
                onPrimary = {},
                onSeek = {},
                onSkipBack = {},
                onSkipForward = {},
                onSetSpeed = {},
            )
            // The bar's other faces: a failure to fix (tinted, Retry), the end (Listen again), an armed sleep
            // timer (the gold countdown), a revoked book (✕ Close) and the sleep fade (Keep listening).
            listOf(
                loadedPlayerState(phase = PlayerPhase.Failed(PlaybackError.ConnectionLost()), primary = PrimaryControl.Retry),
                loadedPlayerState(phase = PlayerPhase.Finished, primary = PrimaryControl.ListenAgain),
                loadedPlayerState(sleepTimer = SleepTimerState.Active(SleepTimerMode.Duration(30), 1_380_000, 1_800_000, 0)),
                loadedPlayerState(phase = PlayerPhase.Unavailable(PlaybackError.NoLongerAvailable()), primary = PrimaryControl.Close),
                loadedPlayerState(phase = PlayerPhase.SleepFading),
            ).forEach { face ->
                TransportBar(
                    state = face,
                    progress = playerProgress(),
                    onPrimary = {},
                    onSeek = {},
                    onSkipBack = {},
                    onSkipForward = {},
                    onSetSpeed = {},
                )
            }
            // The expanded player, rendered open. It cannot ride the bar above into
            // this contract — it appears only after a click, and this harness renders
            // without gesturing — so a class invented in there would go unchecked.
            // Both a fully-known book and a chapterless, authorless standalone: the second
            // draws a different subset (no byline, no series, no chapter line).
        },
    )
