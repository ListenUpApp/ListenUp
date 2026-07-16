@file:OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.campfire

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.campfire.CampfireAnchor
import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireFrame
import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.CampfireInvitableUser
import com.calypsan.listenup.api.dto.campfire.CampfireMember
import com.calypsan.listenup.api.dto.campfire.CampfirePhase
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.api.dto.campfire.CampfireSnapshot
import com.calypsan.listenup.api.dto.campfire.ChatMessage
import com.calypsan.listenup.api.dto.campfire.OpenCampfireSummary
import com.calypsan.listenup.api.dto.campfire.PlaybackCommand
import com.calypsan.listenup.api.error.CampfireError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.domain.playback.PlaybackTimeline
import com.calypsan.listenup.client.playback.PlaybackManager
import com.calypsan.listenup.client.test.fake.FakePlaybackController
import com.calypsan.listenup.client.test.fake.FakePlaybackManager
import com.calypsan.listenup.core.BookId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.ints.shouldBeGreaterThan
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * TDD suite for [CampfireSessionController] (campfire implementation plan, Task 8). Drives the
 * controller against [FakeCampfireTransport] and the shared [FakePlaybackManager]/
 * [FakePlaybackController] fakes under virtual time (no real RPC socket, no real player).
 */
class CampfireSessionControllerTest :
    FunSpec({

        val sessionId = CampfireId("cf-1")

        fun anchor(
            positionMs: Long = 0L,
            capturedAtEpochMs: Long = 0L,
            speed: Float = 1.0f,
            isPlaying: Boolean = false,
            stateVersion: Long = 0L,
        ) = CampfireAnchor(positionMs, capturedAtEpochMs, speed, isPlaying, stateVersion)

        fun snapshot(
            anchor: CampfireAnchor = anchor(),
            hostUserId: String = "host-1",
            controlMode: CampfireControlMode = CampfireControlMode.EVERYONE,
            members: List<CampfireMember> = emptyList(),
            chat: List<ChatMessage> = emptyList(),
            phase: CampfirePhase = CampfirePhase.LIVE,
            name: String = "Campfire",
            startedAtEpochMs: Long? = null,
            invitedPending: List<CampfireInvitableUser> = emptyList(),
            inviteOnly: Boolean = false,
        ) = CampfireSnapshot(
            id = sessionId,
            bookId = "book-1",
            settings = CampfireSettings(name = name, controlMode = controlMode, inviteOnly = inviteOnly),
            phase = phase,
            anchor = anchor,
            members = members,
            hostUserId = hostUserId,
            recentChat = chat,
            yourPositionMs = null,
            spoilerAhead = false,
            startedAtEpochMs = startedAtEpochMs,
            invitedPending = invitedPending,
        )

        fun controller(
            transport: FakeCampfireTransport,
            scope: CoroutineScope,
            clock: Clock,
            selfUserId: String = "self-1",
            playbackManager: FakePlaybackManager = FakePlaybackManager(),
            playbackController: FakePlaybackController = FakePlaybackController(),
            mainDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        ) = CampfireSessionController(
            transport = transport,
            playbackManager = playbackManager,
            playbackController = playbackController,
            userRepository = FakeUserRepository(selfUserId),
            scope = scope,
            clock = clock,
            mainDispatcher = mainDispatcher,
        )

        fun stubPrepareResult(bookId: BookId = BookId("book-1")) =
            PlaybackManager.PrepareResult(
                timeline =
                    PlaybackTimeline(
                        bookId = bookId,
                        totalDurationMs = 1_800_000L,
                        files =
                            listOf(
                                PlaybackTimeline.FileSegment(
                                    audioFileId = "af-stub",
                                    filename = "stub.m4b",
                                    format = "m4b",
                                    startOffsetMs = 0L,
                                    durationMs = 1_800_000L,
                                    size = 1_000_000L,
                                    streamingUrl = "https://example.test/stub.m4b",
                                    localPath = null,
                                    mediaItemIndex = 0,
                                ),
                            ),
                    ),
                bookTitle = "Stub Book",
                bookAuthor = "Stub Author",
                seriesName = null,
                coverPath = null,
                totalChapters = 1,
                resumePositionMs = 0L,
                resumeSpeed = 1.0f,
            )

        test("join applies the snapshot anchor to playback and seeds Active state") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport =
                    FakeCampfireTransport().apply {
                        joinResult = AppResult.Success(snapshot(anchor = anchor(positionMs = 5_000L, isPlaying = true)))
                    }
                val playbackController = FakePlaybackController()
                val sut = controller(transport, backgroundScope, clock, playbackController = playbackController)

                sut.join(sessionId)

                transport.joinCalls shouldBe listOf(sessionId)
                playbackController.seekCalls shouldBe listOf(5_000L)
                playbackController.playCount shouldBe 1
                playbackController.pauseCount shouldBe 0
                playbackController.speedCalls shouldBe listOf(1.0f)
                val active = sut.state.value as CampfireUiState.Active
                active.sessionId shouldBe sessionId
                active.anchor.positionMs shouldBe 5_000L
            }
        }

        test("foreign AnchorChanged frame is applied via the playback controller") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport = FakeCampfireTransport().apply { joinResult = AppResult.Success(snapshot()) }
                val playbackController = FakePlaybackController()
                val sut = controller(transport, backgroundScope, clock, playbackController = playbackController)
                sut.join(sessionId)
                runCurrent()

                sut.state.test {
                    awaitItem() // initial Active from join

                    val newAnchor = anchor(positionMs = 12_000L, isPlaying = true)
                    transport.emit(RpcEvent.Data(CampfireFrame.AnchorChanged(newAnchor, byUserId = "other-user", commandId = null)))
                    runCurrent()

                    val updated = awaitItem() as CampfireUiState.Active
                    updated.anchor.positionMs shouldBe 12_000L
                    cancelAndIgnoreRemainingEvents()
                }

                playbackController.seekCalls.last() shouldBe 12_000L
                playbackController.playCount shouldBe 1
            }
        }

        test("own command echo is not re-applied to the playback controller") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport = FakeCampfireTransport().apply { joinResult = AppResult.Success(snapshot()) }
                val playbackController = FakePlaybackController()
                val sut = controller(transport, backgroundScope, clock, playbackController = playbackController)
                sut.join(sessionId)
                runCurrent()
                val pauseCountAfterJoin = playbackController.pauseCount // join()'s own anchor apply (default anchor is paused)
                val seekCallsAfterJoin = playbackController.seekCalls.size

                sut.pause()
                runCurrent()
                val commandId = transport.sentCommands.single().commandId
                playbackController.pauseCount shouldBe pauseCountAfterJoin + 1 // the optimistic apply

                val echoAnchor = anchor(positionMs = 3_000L, isPlaying = false)
                transport.emit(RpcEvent.Data(CampfireFrame.AnchorChanged(echoAnchor, byUserId = "self-1", commandId = commandId)))
                runCurrent()

                // No additional pause/seek/play beyond the original optimistic pause.
                playbackController.pauseCount shouldBe pauseCountAfterJoin + 1
                playbackController.seekCalls.size shouldBe seekCallsAfterJoin
                (sut.state.value as CampfireUiState.Active).anchor.positionMs shouldBe 3_000L
            }
        }

        test("drift beyond tolerance triggers a single corrective seek while the room plays") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport =
                    FakeCampfireTransport().apply {
                        joinResult = AppResult.Success(snapshot(anchor = anchor(positionMs = 0L, isPlaying = true)))
                    }
                val playbackManager = FakePlaybackManager()
                val playbackController = FakePlaybackController()
                val sut = controller(transport, backgroundScope, clock, playbackManager = playbackManager, playbackController = playbackController)
                sut.join(sessionId)
                runCurrent()
                val seekCountAfterJoin = playbackController.seekCalls.size

                // Local playback lags well behind the room's expected position after one tick.
                playbackManager.currentPositionMsFlow.value = 100L
                advanceTimeBy(5_000L)
                runCurrent()

                playbackController.seekCalls.size shouldBe seekCountAfterJoin + 1
                playbackController.seekCalls.last() shouldBe 5_000L
            }
        }

        test("drift within tolerance does not trigger a corrective seek") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport =
                    FakeCampfireTransport().apply {
                        joinResult = AppResult.Success(snapshot(anchor = anchor(positionMs = 0L, isPlaying = true)))
                    }
                val playbackManager = FakePlaybackManager()
                val playbackController = FakePlaybackController()
                val sut = controller(transport, backgroundScope, clock, playbackManager = playbackManager, playbackController = playbackController)
                sut.join(sessionId)
                runCurrent()
                val seekCountAfterJoin = playbackController.seekCalls.size

                playbackManager.currentPositionMsFlow.value = 5_000L
                advanceTimeBy(5_000L)
                runCurrent()

                playbackController.seekCalls.size shouldBe seekCountAfterJoin
            }
        }

        test("drift loop does not correct while local playback is buffering") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport =
                    FakeCampfireTransport().apply {
                        joinResult = AppResult.Success(snapshot(anchor = anchor(positionMs = 0L, isPlaying = true)))
                    }
                val playbackManager = FakePlaybackManager()
                val playbackController = FakePlaybackController()
                val sut = controller(transport, backgroundScope, clock, playbackManager = playbackManager, playbackController = playbackController)
                sut.join(sessionId)
                runCurrent()
                val seekCountAfterJoin = playbackController.seekCalls.size

                playbackManager.isBufferingFlow.value = true
                playbackManager.currentPositionMsFlow.value = 100L
                advanceTimeBy(5_000L)
                runCurrent()

                playbackController.seekCalls.size shouldBe seekCountAfterJoin
            }
        }

        test("pause without control emits ControlDenied and does not send or apply") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport =
                    FakeCampfireTransport().apply {
                        joinResult =
                            AppResult.Success(
                                snapshot(controlMode = CampfireControlMode.HOST_ONLY, hostUserId = "host-1"),
                            )
                    }
                val playbackController = FakePlaybackController()
                val sut = controller(transport, backgroundScope, clock, selfUserId = "self-1", playbackController = playbackController)
                sut.join(sessionId)
                runCurrent()
                val pauseCountAfterJoin = playbackController.pauseCount

                sut.events.test {
                    sut.pause()
                    runCurrent()
                    awaitItem() shouldBe CampfireSessionEvent.ControlDenied
                }

                transport.sentCommands shouldBe emptyList()
                playbackController.pauseCount shouldBe pauseCountAfterJoin
            }
        }

        test("pause with control (host under HOST_ONLY) sends the command and applies optimistically") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport =
                    FakeCampfireTransport().apply {
                        joinResult =
                            AppResult.Success(
                                snapshot(controlMode = CampfireControlMode.HOST_ONLY, hostUserId = "self-1"),
                            )
                    }
                val playbackController = FakePlaybackController()
                val sut = controller(transport, backgroundScope, clock, selfUserId = "self-1", playbackController = playbackController)
                sut.join(sessionId)
                runCurrent()
                val pauseCountAfterJoin = playbackController.pauseCount

                sut.pause()
                runCurrent()

                transport.sentCommands.single().shouldBeInstanceOf<PlaybackCommand.Pause>()
                playbackController.pauseCount shouldBe pauseCountAfterJoin + 1
            }
        }

        test("transport flow error moves state to Disconnected without pausing playback") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport = FakeCampfireTransport().apply { joinResult = AppResult.Success(snapshot()) }
                val playbackController = FakePlaybackController()
                val sut = controller(transport, backgroundScope, clock, playbackController = playbackController)
                sut.join(sessionId)
                runCurrent()
                val pauseCountAfterJoin = playbackController.pauseCount

                transport.emit(RpcEvent.Error(CampfireError.CampfireNotFound()))
                runCurrent()

                val disconnected = sut.state.value as CampfireUiState.Disconnected
                disconnected.sessionId shouldBe sessionId
                disconnected.keepPlayingSolo shouldBe true
                playbackController.pauseCount shouldBe pauseCountAfterJoin
            }
        }

        test("rejoin re-snapshots and returns to Active when drift is small") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport = FakeCampfireTransport().apply { joinResult = AppResult.Success(snapshot()) }
                val playbackManager = FakePlaybackManager()
                val playbackController = FakePlaybackController()
                val sut = controller(transport, backgroundScope, clock, playbackManager = playbackManager, playbackController = playbackController)
                sut.join(sessionId)
                runCurrent()
                transport.emit(RpcEvent.Error(CampfireError.CampfireNotFound()))
                runCurrent()

                playbackManager.currentPositionMsFlow.value = 1_000L
                transport.joinResult = AppResult.Success(snapshot(anchor = anchor(positionMs = 1_500L)))
                sut.rejoin()
                runCurrent()

                val active = sut.state.value as CampfireUiState.Active
                active.pendingRejoinSync shouldBe null
                active.anchor.positionMs shouldBe 1_500L
            }
        }

        test("rejoin with large drift withholds the seek and exposes pendingRejoinSync") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport = FakeCampfireTransport().apply { joinResult = AppResult.Success(snapshot()) }
                val playbackManager = FakePlaybackManager()
                val playbackController = FakePlaybackController()
                val sut = controller(transport, backgroundScope, clock, playbackManager = playbackManager, playbackController = playbackController)
                sut.join(sessionId)
                runCurrent()
                transport.emit(RpcEvent.Error(CampfireError.CampfireNotFound()))
                runCurrent()
                val seekCountAfterDisconnect = playbackController.seekCalls.size

                playbackManager.currentPositionMsFlow.value = 0L
                val farAnchor = anchor(positionMs = 600_000L) // 10 minutes ahead — well past the large-drift threshold
                transport.joinResult = AppResult.Success(snapshot(anchor = farAnchor))
                sut.rejoin()
                runCurrent()

                val active = sut.state.value as CampfireUiState.Active
                active.pendingRejoinSync shouldBe farAnchor
                playbackController.seekCalls.size shouldBe seekCountAfterDisconnect

                sut.confirmRejoinSync()
                runCurrent()
                (sut.state.value as CampfireUiState.Active).pendingRejoinSync shouldBe null
                playbackController.seekCalls.last() shouldBe 600_000L
            }
        }

        test("applies a live anchor to the player through the main dispatcher") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport = FakeCampfireTransport().apply { joinResult = AppResult.Success(snapshot()) }
                val playbackController = FakePlaybackController()
                val main = CountingDispatcher(StandardTestDispatcher(testScheduler))
                val sut = controller(transport, backgroundScope, clock, playbackController = playbackController, mainDispatcher = main)

                sut.join(sessionId) // snapshot() is LIVE → applies the anchor to the player
                advanceUntilIdle()

                // the anchor apply happened AND it was dispatched through the injected main dispatcher
                main.count shouldBeGreaterThan 0
                playbackController.seekCalls shouldBe listOf(0L)
                playbackController.pauseCount shouldBe 1
            }
        }

        test("rejoin refreshes the transport connection before re-joining and re-subscribing") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport = FakeCampfireTransport().apply { joinResult = AppResult.Success(snapshot()) }
                val sut = controller(transport, backgroundScope, clock)
                sut.join(sessionId)
                runCurrent()
                transport.emit(RpcEvent.Error(CampfireError.CampfireNotFound()))
                runCurrent()
                transport.callOrder.clear()

                sut.rejoin()
                runCurrent()

                // The refresh MUST land before the re-join and the re-subscribe — a fresh
                // connection is the whole point (see CampfireTransport.refreshConnection).
                transport.callOrder shouldBe listOf("refreshConnection", "joinSession", "observeSession")
            }
        }

        test("plain join does not refresh the transport connection") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport = FakeCampfireTransport().apply { joinResult = AppResult.Success(snapshot()) }
                val sut = controller(transport, backgroundScope, clock)

                sut.join(sessionId)
                runCurrent()

                transport.callOrder shouldBe listOf("joinSession", "observeSession")
            }
        }

        test("chat and reaction pass-throughs: chat accumulates into state, reactions are one-shot events") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport = FakeCampfireTransport().apply { joinResult = AppResult.Success(snapshot()) }
                val sut = controller(transport, backgroundScope, clock)
                sut.join(sessionId)
                runCurrent()

                sut.sendChat("hello room")
                runCurrent()
                transport.sentChat shouldBe listOf("hello room")

                sut.events.test {
                    transport.emit(RpcEvent.Data(CampfireFrame.Chat(ChatMessage("other", 0L, 0L, "hi back"))))
                    runCurrent()
                    (sut.state.value as CampfireUiState.Active).chat.map { it.text } shouldBe listOf("hi back")

                    transport.emit(RpcEvent.Data(CampfireFrame.Reaction("other", "🔥")))
                    runCurrent()
                    awaitItem() shouldBe CampfireSessionEvent.ReactionReceived("other", "🔥")
                }
            }
        }

        test("CampfireEnded moves state to Ended without pausing local playback") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport = FakeCampfireTransport().apply { joinResult = AppResult.Success(snapshot()) }
                val playbackController = FakePlaybackController()
                val sut = controller(transport, backgroundScope, clock, playbackController = playbackController)
                sut.join(sessionId)
                runCurrent()
                val pauseCountAfterJoin = playbackController.pauseCount

                transport.emit(RpcEvent.Data(CampfireFrame.CampfireEnded("host_ended")))
                runCurrent()

                val ended = sut.state.value as CampfireUiState.Ended
                ended.reason shouldBe "host_ended"
                playbackController.pauseCount shouldBe pauseCountAfterJoin
            }
        }

        test("state is exposed as a StateFlow starting at Idle before join") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport = FakeCampfireTransport()
                val sut = controller(transport, backgroundScope, clock)

                sut.state.value shouldBe CampfireUiState.Idle
            }
        }

        test("leave cancels the frame stream and returns state to Idle") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport = FakeCampfireTransport().apply { joinResult = AppResult.Success(snapshot()) }
                val sut = controller(transport, backgroundScope, clock)
                sut.join(sessionId)
                runCurrent()

                sut.leave()
                runCurrent()

                sut.state.value shouldBe CampfireUiState.Idle
                transport.leaveCalls shouldBe listOf(sessionId)

                // A frame emitted after leave() must not resurrect Active state.
                transport.emit(RpcEvent.Data(CampfireFrame.HostChanged("someone-else")))
                runCurrent()
                sut.state.value shouldBe CampfireUiState.Idle
            }
        }

        test("endCampfire stops the session and calls transport.endSession") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport = FakeCampfireTransport().apply { joinResult = AppResult.Success(snapshot()) }
                val sut = controller(transport, backgroundScope, clock)
                sut.join(sessionId)
                runCurrent()

                sut.endCampfire()
                runCurrent()

                sut.state.value shouldBe CampfireUiState.Idle
                transport.endCalls shouldBe listOf(sessionId)
            }
        }

        test("leave dispatches the server leaveSession on the controller scope, not the calling coroutine") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport = FakeCampfireTransport().apply { joinResult = AppResult.Success(snapshot()) }
                val sut = controller(transport, backgroundScope, clock)
                sut.join(sessionId)
                runCurrent()

                sut.leave()

                // State flips immediately so readers see the teardown; the terminal RPC is handed to
                // the controller's own scope (appScope in prod) so a torn-down caller can't strand it.
                sut.state.value shouldBe CampfireUiState.Idle
                transport.leaveCalls shouldBe emptyList()
                runCurrent()
                transport.leaveCalls shouldBe listOf(sessionId)
            }
        }

        test("endCampfire dispatches the server endSession on the controller scope, not the calling coroutine") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport = FakeCampfireTransport().apply { joinResult = AppResult.Success(snapshot()) }
                val sut = controller(transport, backgroundScope, clock)
                sut.join(sessionId)
                runCurrent()

                sut.endCampfire()

                sut.state.value shouldBe CampfireUiState.Idle
                transport.endCalls shouldBe emptyList()
                runCurrent()
                transport.endCalls shouldBe listOf(sessionId)
            }
        }

        test("exitForPlayback ends the campfire when the local user is currently the host") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport =
                    FakeCampfireTransport().apply { joinResult = AppResult.Success(snapshot(hostUserId = "self-1")) }
                val sut = controller(transport, backgroundScope, clock, selfUserId = "self-1")
                sut.join(sessionId)
                runCurrent()
                (sut.state.value as CampfireUiState.Active).isHost shouldBe true

                sut.exitForPlayback()
                runCurrent()

                transport.endCalls shouldBe listOf(sessionId)
                transport.leaveCalls shouldBe emptyList()
            }
        }

        test("exitForPlayback leaves — not ends — after a host transfer makes the local user a participant") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport =
                    FakeCampfireTransport().apply { joinResult = AppResult.Success(snapshot(hostUserId = "self-1")) }
                val sut = controller(transport, backgroundScope, clock, selfUserId = "self-1")
                sut.join(sessionId)
                runCurrent()

                // The host hands control to someone else — the local user is now a participant, so
                // exiting to play another book must LEAVE, not END everyone's session.
                transport.emit(RpcEvent.Data(CampfireFrame.HostChanged("other-user")))
                runCurrent()
                (sut.state.value as CampfireUiState.Active).isHost shouldBe false

                sut.exitForPlayback()
                runCurrent()

                transport.leaveCalls shouldBe listOf(sessionId)
                transport.endCalls shouldBe emptyList()
            }
        }

        test("exitForPlayback still ends the campfire when the host disconnected while the confirm was open") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport =
                    FakeCampfireTransport().apply { joinResult = AppResult.Success(snapshot(hostUserId = "self-1")) }
                val sut = controller(transport, backgroundScope, clock, selfUserId = "self-1")
                sut.join(sessionId)
                runCurrent()

                // The frame stream drops → Disconnected, but the play-over-campfire confirm dialog is
                // still open. A host confirming "end for everyone" must still END, not silently leave.
                transport.emit(RpcEvent.Error(CampfireError.CampfireNotFound()))
                runCurrent()
                sut.state.value.shouldBeInstanceOf<CampfireUiState.Disconnected>()

                sut.exitForPlayback()
                runCurrent()

                transport.endCalls shouldBe listOf(sessionId)
                transport.leaveCalls shouldBe emptyList()
            }
        }

        test("join drains stale one-shot events left buffered by a previous session on the singleton controller") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport = FakeCampfireTransport().apply { joinResult = AppResult.Success(snapshot()) }
                val sut = controller(transport, backgroundScope, clock)
                sut.join(sessionId)
                runCurrent()

                // A reaction arrives with no events collector (the campfire screen is gone) → it buffers
                // on the controller's channel, which now outlives the session (F2 made it a singleton).
                transport.emit(RpcEvent.Data(CampfireFrame.Reaction("ghost", "🔥")))
                runCurrent()
                sut.leave()
                runCurrent()

                // A fresh session on the SAME controller must not replay the previous session's events.
                sut.join(sessionId)
                runCurrent()
                sut.events.test {
                    transport.emit(RpcEvent.Data(CampfireFrame.Reaction("live", "✨")))
                    awaitItem() shouldBe CampfireSessionEvent.ReactionReceived("live", "✨")
                }
            }
        }

        test("a second join on the singleton controller yields a clean Active state with no bleed from the first") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport = FakeCampfireTransport()
                val sut = controller(transport, backgroundScope, clock, selfUserId = "self-1")

                // Session 1: the local user is the host.
                transport.joinResult = AppResult.Success(snapshot(hostUserId = "self-1"))
                sut.join(sessionId)
                runCurrent()
                (sut.state.value as CampfireUiState.Active).isHost shouldBe true
                sut.leave()
                runCurrent()

                // Session 2 (different room) where the local user is NOT the host — the reused
                // singleton must reflect ONLY session 2, with no host role or ids carried over.
                val other = CampfireId("cf-2")
                transport.joinResult =
                    AppResult.Success(snapshot(hostUserId = "someone-else").copy(id = other, bookId = "book-2"))
                sut.join(other)
                runCurrent()

                val active = sut.state.value as CampfireUiState.Active
                active.sessionId shouldBe other
                active.bookId shouldBe "book-2"
                active.isHost shouldBe false
            }
        }

        // ── Lobby phase (2026-07-11 amendment) ───────────────────────────────

        test("join with a LOBBY snapshot does not touch local playback") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport =
                    FakeCampfireTransport().apply {
                        joinResult =
                            AppResult.Success(
                                snapshot(phase = CampfirePhase.LOBBY, anchor = anchor(positionMs = 5_000L, isPlaying = false)),
                            )
                    }
                val playbackController = FakePlaybackController()
                val sut = controller(transport, backgroundScope, clock, playbackController = playbackController)

                sut.join(sessionId)
                runCurrent()

                playbackController.seekCalls shouldBe emptyList()
                playbackController.playCount shouldBe 0
                playbackController.pauseCount shouldBe 0
                playbackController.speedCalls shouldBe emptyList()
                val active = sut.state.value as CampfireUiState.Active
                active.phase shouldBe CampfirePhase.LOBBY
            }
        }

        test("CampfireStarted frame applies the anchor to playback and flips phase to LIVE") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport =
                    FakeCampfireTransport().apply {
                        joinResult =
                            AppResult.Success(snapshot(phase = CampfirePhase.LOBBY, anchor = anchor(positionMs = 0L, isPlaying = false)))
                    }
                val playbackController = FakePlaybackController()
                val sut = controller(transport, backgroundScope, clock, playbackController = playbackController)
                sut.join(sessionId)
                runCurrent()

                val startedAnchor = anchor(positionMs = 3_000L, isPlaying = true)
                transport.emit(RpcEvent.Data(CampfireFrame.CampfireStarted(anchor = startedAnchor, byUserId = "host-1")))
                runCurrent()

                playbackController.seekCalls.last() shouldBe 3_000L
                playbackController.playCount shouldBe 1
                val active = sut.state.value as CampfireUiState.Active
                active.phase shouldBe CampfirePhase.LIVE
                active.startedAtEpochMs shouldBe startedAnchor.capturedAtEpochMs
                active.anchor shouldBe startedAnchor
            }
        }

        test("LOBBY join does not load the campfire book — the fire is not lit yet") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport =
                    FakeCampfireTransport().apply {
                        joinResult =
                            AppResult.Success(snapshot(phase = CampfirePhase.LOBBY, anchor = anchor(positionMs = 0L, isPlaying = false)))
                    }
                val playbackManager = FakePlaybackManager().apply { stubbedPrepareResult = stubPrepareResult() }
                val sut = controller(transport, backgroundScope, clock, playbackManager = playbackManager)

                sut.join(sessionId)
                runCurrent()

                playbackManager.prepareForPlaybackCalls shouldBe emptyList()
            }
        }

        test("CampfireStarted loads the campfire book, then applies the play anchor") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport =
                    FakeCampfireTransport().apply {
                        joinResult =
                            AppResult.Success(snapshot(phase = CampfirePhase.LOBBY, anchor = anchor(positionMs = 0L, isPlaying = false)))
                    }
                val playbackManager = FakePlaybackManager().apply { stubbedPrepareResult = stubPrepareResult() }
                val playbackController = FakePlaybackController()
                val sut =
                    controller(transport, backgroundScope, clock, playbackManager = playbackManager, playbackController = playbackController)
                sut.join(sessionId)
                runCurrent()

                transport.emit(RpcEvent.Data(CampfireFrame.CampfireStarted(anchor = anchor(positionMs = 3_000L, isPlaying = true), byUserId = "host-1")))
                runCurrent()

                playbackManager.prepareForPlaybackCalls shouldBe listOf(BookId("book-1"))
                playbackController.startPlaybackCalls.size shouldBe 1
                playbackController.seekCalls.last() shouldBe 3_000L
                playbackController.playCount shouldBe 1
            }
        }

        test("joining an already-LIVE room loads the book before applying the anchor") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport =
                    FakeCampfireTransport().apply {
                        joinResult =
                            AppResult.Success(snapshot(phase = CampfirePhase.LIVE, anchor = anchor(positionMs = 5_000L, isPlaying = true)))
                    }
                val playbackManager = FakePlaybackManager().apply { stubbedPrepareResult = stubPrepareResult() }
                val playbackController = FakePlaybackController()
                val sut =
                    controller(transport, backgroundScope, clock, playbackManager = playbackManager, playbackController = playbackController)

                sut.join(sessionId)
                runCurrent()

                playbackManager.prepareForPlaybackCalls shouldBe listOf(BookId("book-1"))
                playbackController.startPlaybackCalls.size shouldBe 1
                playbackController.seekCalls.last() shouldBe 5_000L
            }
        }

        test("drift loop does not correct while the room is still in LOBBY") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport =
                    FakeCampfireTransport().apply {
                        joinResult =
                            AppResult.Success(snapshot(phase = CampfirePhase.LOBBY, anchor = anchor(positionMs = 0L, isPlaying = true)))
                    }
                val playbackManager = FakePlaybackManager()
                val playbackController = FakePlaybackController()
                val sut =
                    controller(
                        transport,
                        backgroundScope,
                        clock,
                        playbackManager = playbackManager,
                        playbackController = playbackController,
                    )
                sut.join(sessionId)
                runCurrent()
                val seekCountAfterJoin = playbackController.seekCalls.size

                playbackManager.currentPositionMsFlow.value = 100L
                advanceTimeBy(5_000L)
                runCurrent()

                playbackController.seekCalls.size shouldBe seekCountAfterJoin
            }
        }

        test("startCampfire delegates to transport.startSession for the active session") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport = FakeCampfireTransport().apply { joinResult = AppResult.Success(snapshot(phase = CampfirePhase.LOBBY)) }
                val sut = controller(transport, backgroundScope, clock)
                sut.join(sessionId)
                runCurrent()

                sut.startCampfire()

                transport.startSessionCalls shouldBe listOf(sessionId)
            }
        }

        test("pause while still in LOBBY emits NotStarted and does not send or apply") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport = FakeCampfireTransport().apply { joinResult = AppResult.Success(snapshot(phase = CampfirePhase.LOBBY)) }
                val playbackController = FakePlaybackController()
                val sut = controller(transport, backgroundScope, clock, playbackController = playbackController)
                sut.join(sessionId)
                runCurrent()
                val pauseCountAfterJoin = playbackController.pauseCount

                sut.events.test {
                    sut.pause()
                    runCurrent()
                    awaitItem() shouldBe CampfireSessionEvent.NotStarted
                }

                transport.sentCommands shouldBe emptyList()
                playbackController.pauseCount shouldBe pauseCountAfterJoin
            }
        }

        test("MemberJoined removes the joining user from invitedPending") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val pendingUser = CampfireInvitableUser(userId = "u2", displayName = "Bob")
                val transport =
                    FakeCampfireTransport().apply {
                        joinResult =
                            AppResult.Success(snapshot(phase = CampfirePhase.LOBBY, invitedPending = listOf(pendingUser)))
                    }
                val sut = controller(transport, backgroundScope, clock)
                sut.join(sessionId)
                runCurrent()
                (sut.state.value as CampfireUiState.Active).invitedPending shouldBe listOf(pendingUser)

                transport.emit(
                    RpcEvent.Data(
                        CampfireFrame.MemberJoined(
                            CampfireMember(userId = "u2", displayName = "Bob", joinedAtEpochMs = 0L, isAway = false, invited = false),
                        ),
                    ),
                )
                runCurrent()

                (sut.state.value as CampfireUiState.Active).invitedPending shouldBe emptyList()
            }
        }

        test("updateSettings success refreshes name, controlMode, invitedPending, and inviteOnly from the returned snapshot") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val transport =
                    FakeCampfireTransport().apply {
                        joinResult = AppResult.Success(snapshot(phase = CampfirePhase.LOBBY, name = "Old Name", inviteOnly = false))
                    }
                val sut = controller(transport, backgroundScope, clock)
                sut.join(sessionId)
                runCurrent()
                (sut.state.value as CampfireUiState.Active).inviteOnly shouldBe false

                val newPending = listOf(CampfireInvitableUser(userId = "u3", displayName = "Carol"))
                val newSettings =
                    CampfireSettings(
                        name = "New Name",
                        controlMode = CampfireControlMode.HOST_ONLY,
                        inviteOnly = true,
                        invitedUserIds = listOf("u3"),
                    )
                transport.updateSettingsResult =
                    AppResult.Success(
                        snapshot(
                            phase = CampfirePhase.LOBBY,
                            name = "New Name",
                            controlMode = CampfireControlMode.HOST_ONLY,
                            invitedPending = newPending,
                            inviteOnly = true,
                        ),
                    )

                sut.updateSettings(newSettings)
                runCurrent()

                transport.updateSettingsCalls shouldBe listOf(newSettings)
                val active = sut.state.value as CampfireUiState.Active
                active.name shouldBe "New Name"
                active.controlMode shouldBe CampfireControlMode.HOST_ONLY
                active.invitedPending shouldBe newPending
                active.inviteOnly shouldBe true
            }
        }
    })

/** Bridges [kotlin.time.Clock] to the test scheduler's virtual time — mirrors CachedAudioTokenProviderTest's VirtualClock. */
private class VirtualClock(
    private val scheduler: TestCoroutineScheduler,
) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(scheduler.currentTime)
}

/** Counts every dispatch through [delegate] — proves a call was actually marshalled through the injected main dispatcher. */
private class CountingDispatcher(
    private val delegate: CoroutineDispatcher,
) : CoroutineDispatcher() {
    var count = 0
        private set

    override fun dispatch(
        context: kotlin.coroutines.CoroutineContext,
        block: Runnable,
    ) {
        count++
        delegate.dispatch(context, block)
    }
}

/** In-memory [FakeCampfireTransport] — records every call, replays a hot frame flow for [observeSession]. */
private class FakeCampfireTransport : CampfireTransport {
    var joinResult: AppResult<CampfireSnapshot> = AppResult.Failure(CampfireError.CampfireNotFound())
    val joinCalls = mutableListOf<CampfireId>()
    val leaveCalls = mutableListOf<CampfireId>()
    val endCalls = mutableListOf<CampfireId>()
    val sentCommands = mutableListOf<PlaybackCommand>()
    var sendCommandResult: AppResult<Unit> = AppResult.Success(Unit)
    val sentChat = mutableListOf<String>()
    val sentReactions = mutableListOf<String>()
    val startSessionCalls = mutableListOf<CampfireId>()
    var startSessionResult: AppResult<Unit> = AppResult.Success(Unit)
    val updateSettingsCalls = mutableListOf<CampfireSettings>()
    var updateSettingsResult: AppResult<CampfireSnapshot> = AppResult.Failure(CampfireError.CampfireNotFound())

    /** Ordered log of connection-lifecycle calls — proves rejoin refreshes BEFORE re-joining/re-subscribing. */
    val callOrder = mutableListOf<String>()

    private val frameFlow = MutableSharedFlow<RpcEvent<CampfireFrame>>(extraBufferCapacity = 64)

    suspend fun emit(event: RpcEvent<CampfireFrame>) = frameFlow.emit(event)

    override suspend fun createSession(
        bookId: String,
        settings: CampfireSettings,
    ): AppResult<CampfireSnapshot> = throw NotImplementedError("not exercised by CampfireSessionController")

    override suspend fun joinSession(sessionId: CampfireId): AppResult<CampfireSnapshot> {
        joinCalls += sessionId
        callOrder += "joinSession"
        return joinResult
    }

    override suspend fun refreshConnection() {
        callOrder += "refreshConnection"
    }

    override suspend fun leaveSession(sessionId: CampfireId): AppResult<Unit> {
        leaveCalls += sessionId
        return AppResult.Success(Unit)
    }

    override suspend fun endSession(sessionId: CampfireId): AppResult<Unit> {
        endCalls += sessionId
        return AppResult.Success(Unit)
    }

    override suspend fun startSession(sessionId: CampfireId): AppResult<Unit> {
        startSessionCalls += sessionId
        return startSessionResult
    }

    override suspend fun updateSettings(
        sessionId: CampfireId,
        settings: CampfireSettings,
    ): AppResult<CampfireSnapshot> {
        updateSettingsCalls += settings
        return updateSettingsResult
    }

    override suspend fun transferHost(
        sessionId: CampfireId,
        toUserId: String,
    ): AppResult<Unit> = throw NotImplementedError()

    override suspend fun setControlMode(
        sessionId: CampfireId,
        mode: CampfireControlMode,
    ): AppResult<Unit> = throw NotImplementedError()

    override fun observeSession(sessionId: CampfireId): Flow<RpcEvent<CampfireFrame>> {
        callOrder += "observeSession"
        return frameFlow
    }

    override suspend fun sendCommand(
        sessionId: CampfireId,
        command: PlaybackCommand,
    ): AppResult<Unit> {
        sentCommands += command
        return sendCommandResult
    }

    override suspend fun sendChat(
        sessionId: CampfireId,
        text: String,
    ): AppResult<Unit> {
        sentChat += text
        return AppResult.Success(Unit)
    }

    override suspend fun sendReaction(
        sessionId: CampfireId,
        emoji: String,
    ): AppResult<Unit> {
        sentReactions += emoji
        return AppResult.Success(Unit)
    }

    override suspend fun listOpenSessions(): AppResult<List<OpenCampfireSummary>> = AppResult.Success(emptyList())

    override suspend fun listInvitableUsers(bookId: String): AppResult<List<CampfireInvitableUser>> = AppResult.Success(emptyList())
}

/** Minimal [UserRepository] fake — only [getCurrentUser] is reachable from [CampfireSessionController]. */
private class FakeUserRepository(
    private val selfUserId: String?,
) : UserRepository {
    override fun observeCurrentUser(): Flow<User?> = throw NotImplementedError()

    override fun observeIsAdmin(): Flow<Boolean> = throw NotImplementedError()

    override suspend fun getCurrentUser(): User? =
        selfUserId?.let {
            User(
                id = UserId(it),
                email = "$it@example.test",
                displayName = it,
                isAdmin = false,
                createdAtMs = 0L,
                updatedAtMs = 0L,
            )
        }

    override suspend fun saveUser(user: User): Unit = throw NotImplementedError()

    override suspend fun clearUsers(): Unit = throw NotImplementedError()

    override suspend fun refreshCurrentUser(): User? = throw NotImplementedError()
}
