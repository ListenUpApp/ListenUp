package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.api.error.PlaybackError
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class PlayerPhaseDerivationTest :
    FunSpec({
        val book = BookId("b1")

        fun inputs(
            enginePhase: EnginePhase = EnginePhase.IDLE,
            engineBook: BookId? = null,
            engineError: com.calypsan.listenup.api.error.AppError? = null,
            isFading: Boolean = false,
            isFinishedRow: Boolean = false,
            unavailable: com.calypsan.listenup.api.error.AppError? = null,
        ) = PhaseInputs(book, enginePhase, engineError, engineBook, isFading, isFinishedRow, unavailable)

        context("not prepared (Idle stage)") {
            test("restores paused") { playerPhaseOf(SessionStage.Idle(book), inputs()) shouldBe PlayerPhase.Restored }
            test("a finished row reads Finished") {
                playerPhaseOf(SessionStage.Idle(book), inputs(isFinishedRow = true)) shouldBe
                    PlayerPhase.Finished
            }
            test("a proactive reason wins") {
                playerPhaseOf(SessionStage.Idle(book), inputs(unavailable = PlaybackError.OfflineNotDownloaded())) shouldBe
                    PlayerPhase.Unavailable(PlaybackError.OfflineNotDownloaded())
            }
        }

        context("engine stage") {
            test("each engine phase") {
                val s = SessionStage.Engine(book)
                playerPhaseOf(s, inputs(EnginePhase.IDLE, book)) shouldBe PlayerPhase.Preparing
                playerPhaseOf(s, inputs(EnginePhase.BUFFERING, book)) shouldBe PlayerPhase.Buffering
                playerPhaseOf(s, inputs(EnginePhase.PLAYING, book)) shouldBe PlayerPhase.Playing
                playerPhaseOf(s, inputs(EnginePhase.PLAYING, book, isFading = true)) shouldBe PlayerPhase.SleepFading
                playerPhaseOf(s, inputs(EnginePhase.PAUSED, book)) shouldBe PlayerPhase.Paused
                playerPhaseOf(s, inputs(EnginePhase.PAUSED, book, isFinishedRow = true)) shouldBe PlayerPhase.Finished
                playerPhaseOf(s, inputs(EnginePhase.ENDED, book)) shouldBe PlayerPhase.Finished
            }
            test("a stall reads Reconnecting only inside a drop the network caused") {
                val s = SessionStage.Engine(book)
                val stalled = inputs(EnginePhase.BUFFERING, book)
                playerPhaseOf(s, stalled.copy(drop = DropStatus.Quiet)) shouldBe PlayerPhase.Buffering
                playerPhaseOf(s, stalled.copy(drop = DropStatus.Reconnecting)) shouldBe PlayerPhase.Reconnecting
                val failing = inputs(EnginePhase.PAUSED, book, engineError = PlaybackError.ConnectionLost())
                playerPhaseOf(s, failing.copy(drop = DropStatus.Reconnecting)) shouldBe PlayerPhase.Reconnecting
                playerPhaseOf(s, failing.copy(drop = DropStatus.Surfaced)) shouldBe
                    PlayerPhase.Failed(PlaybackError.ConnectionLost())
            }
            test("an engine a surfaced failure stopped, left idle, waits for Play") {
                playerPhaseOf(SessionStage.Engine(book, halted = true), inputs(EnginePhase.IDLE, book)) shouldBe
                    PlayerPhase.Paused
            }
            test("an engine error is Failed") {
                playerPhaseOf(
                    SessionStage.Engine(book),
                    inputs(EnginePhase.PAUSED, book, engineError = PlaybackError.ConnectionLost()),
                ) shouldBe
                    PlayerPhase.Failed(PlaybackError.ConnectionLost())
            }
            test("a dropped stream while the book can't be reached is that reason, as a failed prepare is") {
                val dropped = PlaybackError.ConnectionLost()
                val s = SessionStage.Engine(book)
                playerPhaseOf(
                    s,
                    inputs(EnginePhase.PAUSED, book, engineError = dropped, unavailable = PlaybackError.ServerUnreachable()),
                ) shouldBe PlayerPhase.Failed(PlaybackError.ServerUnreachable())
                playerPhaseOf(
                    s,
                    inputs(EnginePhase.PAUSED, book, engineError = dropped, unavailable = PlaybackError.OfflineNotDownloaded()),
                ) shouldBe PlayerPhase.Failed(PlaybackError.OfflineNotDownloaded())
            }
            test("only a dropped stream is explained by reachability; other reasons and failures stand") {
                val s = SessionStage.Engine(book)
                playerPhaseOf(
                    s,
                    inputs(
                        EnginePhase.PAUSED,
                        book,
                        engineError = PlaybackError.UnsupportedFormat(),
                        unavailable = PlaybackError.ServerUnreachable(),
                    ),
                ) shouldBe PlayerPhase.Failed(PlaybackError.UnsupportedFormat())
                playerPhaseOf(
                    s,
                    inputs(
                        EnginePhase.PAUSED,
                        book,
                        engineError = PlaybackError.ConnectionLost(),
                        unavailable = BookError.HeldForReview(),
                    ),
                ) shouldBe PlayerPhase.Failed(PlaybackError.ConnectionLost())
            }
            test("a proactive reason does not interrupt a book the engine holds") {
                playerPhaseOf(
                    SessionStage.Engine(book),
                    inputs(EnginePhase.PLAYING, book, unavailable = PlaybackError.ServerUnreachable()),
                ) shouldBe
                    PlayerPhase.Playing
            }
        }

        test("preparing, failed and gone stages") {
            playerPhaseOf(SessionStage.Preparing(book, generation = 1), inputs()) shouldBe PlayerPhase.Preparing
            playerPhaseOf(SessionStage.Failed(book, PlaybackError.CouldNotStart()), inputs()) shouldBe
                PlayerPhase.Failed(PlaybackError.CouldNotStart())
            playerPhaseOf(SessionStage.Gone(book), inputs()) shouldBe PlayerPhase.Unavailable(PlaybackError.NoLongerAvailable())
        }

        context("unavailableReasonOf (§4.6 proactive causes)") {
            test("held first") {
                unavailableReasonOf(
                    isHeld = true,
                    isSessionLapsed = true,
                    hasDownloadedAudio = false,
                    canPlay = false,
                    isDeviceOnline = false,
                    offlineCopiesSupported = true,
                ) shouldBe
                    BookError.HeldForReview()
            }
            test("a lapsed session matters only with nothing downloaded") {
                unavailableReasonOf(
                    false,
                    isSessionLapsed = true,
                    hasDownloadedAudio = false,
                    canPlay = true,
                    isDeviceOnline = true,
                    offlineCopiesSupported = true,
                ) shouldBe
                    AuthError.SessionExpired()
                unavailableReasonOf(
                    false,
                    isSessionLapsed = true,
                    hasDownloadedAudio = true,
                    canPlay = true,
                    isDeviceOnline = true,
                    offlineCopiesSupported = true,
                ).shouldBeNull()
            }
            test("can't play: offline native says offline, everything else says unreachable") {
                unavailableReasonOf(false, false, false, canPlay = false, isDeviceOnline = false, offlineCopiesSupported = true) shouldBe
                    PlaybackError.OfflineNotDownloaded()
                unavailableReasonOf(false, false, false, canPlay = false, isDeviceOnline = true, offlineCopiesSupported = true) shouldBe
                    PlaybackError.ServerUnreachable()
                unavailableReasonOf(false, false, false, canPlay = false, isDeviceOnline = false, offlineCopiesSupported = false) shouldBe
                    PlaybackError.ServerUnreachable()
            }
            test("playable means no reason") {
                unavailableReasonOf(
                    false,
                    false,
                    false,
                    canPlay = true,
                    isDeviceOnline = true,
                    offlineCopiesSupported = true,
                ).shouldBeNull()
            }
        }

        context("livenessOf (§3.4 revoke/delete)") {
            test("a row that is gone") {
                livenessOf(rowExists = false, isLive = false, isDownloaded = false, positionMs = 0, isFinished = false) shouldBe
                    Liveness.RowGone
            }
            test("a revoked streaming book") {
                livenessOf(true, isLive = false, isDownloaded = false, positionMs = 5_000, isFinished = false) shouldBe
                    Liveness.Revoked
            }
            test("a revoked downloaded in-progress book keeps playing") {
                livenessOf(true, false, isDownloaded = true, positionMs = 5_000, isFinished = false) shouldBe
                    Liveness.Live
            }
            test("a revoked downloaded book never started drops") {
                livenessOf(true, false, isDownloaded = true, positionMs = 0, isFinished = false) shouldBe
                    Liveness.Revoked
            }
            test("a live book") {
                livenessOf(true, isLive = true, isDownloaded = false, positionMs = 0, isFinished = false) shouldBe
                    Liveness.Live
            }
        }
    })
