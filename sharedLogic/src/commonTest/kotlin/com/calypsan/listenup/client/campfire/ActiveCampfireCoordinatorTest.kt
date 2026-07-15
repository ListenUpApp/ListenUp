@file:OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.campfire

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.campfire.CampfireAnchor
import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireFrame
import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.CampfireInvitableUser
import com.calypsan.listenup.api.dto.campfire.CampfirePhase
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.api.dto.campfire.CampfireSnapshot
import com.calypsan.listenup.api.dto.campfire.OpenCampfireSummary
import com.calypsan.listenup.api.dto.campfire.PlaybackCommand
import com.calypsan.listenup.api.error.CampfireError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.test.fake.FakePlaybackController
import com.calypsan.listenup.client.test.fake.FakePlaybackManager
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * TDD suite for [ActiveCampfireCoordinator] — the process-scope liveness seam that mirrors the live
 * [CampfireSessionController]'s state so the singleton `NowPlayingViewModel` can guard "play a
 * different book" against a live campfire (co-listening coexistence spec, B3 / ownership follow-up
 * F2). The coordinator OWNS the always-on mirror at process scope; it no longer depends on any
 * ViewModel being alive.
 */
class ActiveCampfireCoordinatorTest :
    FunSpec({

        val sessionId = CampfireId("cf-1")

        fun snapshot(hostUserId: String = "host-1") =
            CampfireSnapshot(
                id = sessionId,
                bookId = "book-1",
                settings = CampfireSettings(name = "Campfire", controlMode = CampfireControlMode.EVERYONE, inviteOnly = false),
                phase = CampfirePhase.LIVE,
                anchor = CampfireAnchor(0L, 0L, 1.0f, isPlaying = false, stateVersion = 0L),
                members = emptyList(),
                hostUserId = hostUserId,
                recentChat = emptyList(),
                yourPositionMs = null,
                spoilerAhead = false,
                startedAtEpochMs = null,
                invitedPending = emptyList(),
            )

        fun controller(
            transport: FakeCoordinatorTransport,
            scope: CoroutineScope,
            selfUserId: String = "self-1",
        ) = CampfireSessionController(
            transport = transport,
            playbackManager = FakePlaybackManager(),
            playbackController = FakePlaybackController(),
            userRepository = FakeUserRepo(selfUserId),
            scope = scope,
            clock = FixedClock,
            mainDispatcher = Dispatchers.Unconfined,
        )

        test("current starts null with no live session") {
            runTest {
                val controller = controller(FakeCoordinatorTransport(), backgroundScope)
                val coordinator = ActiveCampfireCoordinator(controller, backgroundScope)
                runCurrent()

                coordinator.current.value shouldBe null
            }
        }

        test("mirrors the controller's live session onto current, driven on the injected scope") {
            runTest {
                val transport = FakeCoordinatorTransport().apply { joinResult = AppResult.Success(snapshot()) }
                val controller = controller(transport, backgroundScope)
                val coordinator = ActiveCampfireCoordinator(controller, backgroundScope)

                controller.join(sessionId)
                runCurrent()

                coordinator.current.value shouldBe
                    ActiveCampfire(sessionId = sessionId, bookId = "book-1", isHost = false, phase = CampfirePhase.LIVE)
            }
        }

        test("clears current when the session leaves") {
            runTest {
                val transport = FakeCoordinatorTransport().apply { joinResult = AppResult.Success(snapshot()) }
                val controller = controller(transport, backgroundScope)
                val coordinator = ActiveCampfireCoordinator(controller, backgroundScope)

                controller.join(sessionId)
                runCurrent()
                controller.leave()
                runCurrent()

                coordinator.current.value shouldBe null
            }
        }
    })

/** Minimal [CampfireTransport] for the coordinator suite — only the calls the controller makes on join/leave. */
private class FakeCoordinatorTransport : CampfireTransport {
    var joinResult: AppResult<CampfireSnapshot> = AppResult.Failure(CampfireError.CampfireNotFound())
    private val frameFlow = MutableSharedFlow<RpcEvent<CampfireFrame>>(extraBufferCapacity = 64)

    override suspend fun joinSession(sessionId: CampfireId): AppResult<CampfireSnapshot> = joinResult

    override fun observeSession(sessionId: CampfireId): Flow<RpcEvent<CampfireFrame>> = frameFlow

    override suspend fun leaveSession(sessionId: CampfireId): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun endSession(sessionId: CampfireId): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun refreshConnection() = Unit

    override suspend fun createSession(
        bookId: String,
        settings: CampfireSettings,
    ): AppResult<CampfireSnapshot> = throw NotImplementedError()

    override suspend fun startSession(sessionId: CampfireId): AppResult<Unit> = throw NotImplementedError()

    override suspend fun updateSettings(
        sessionId: CampfireId,
        settings: CampfireSettings,
    ): AppResult<CampfireSnapshot> = throw NotImplementedError()

    override suspend fun transferHost(
        sessionId: CampfireId,
        userId: String,
    ): AppResult<Unit> = throw NotImplementedError()

    override suspend fun setControlMode(
        sessionId: CampfireId,
        mode: CampfireControlMode,
    ): AppResult<Unit> = throw NotImplementedError()

    override suspend fun sendCommand(
        sessionId: CampfireId,
        command: PlaybackCommand,
    ): AppResult<Unit> = throw NotImplementedError()

    override suspend fun sendChat(
        sessionId: CampfireId,
        text: String,
    ): AppResult<Unit> = throw NotImplementedError()

    override suspend fun sendReaction(
        sessionId: CampfireId,
        emoji: String,
    ): AppResult<Unit> = throw NotImplementedError()

    override suspend fun listOpenSessions(): AppResult<List<OpenCampfireSummary>> = throw NotImplementedError()

    override suspend fun listInvitableUsers(bookId: String): AppResult<List<CampfireInvitableUser>> = throw NotImplementedError()
}

private class FakeUserRepo(
    private val selfUserId: String?,
) : UserRepository {
    private fun currentUser(): User? =
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

    override fun observeCurrentUser(): Flow<User?> = kotlinx.coroutines.flow.flowOf(currentUser())

    override fun observeIsAdmin(): Flow<Boolean> = throw NotImplementedError()

    override suspend fun getCurrentUser(): User? = currentUser()

    override suspend fun saveUser(user: User): Unit = throw NotImplementedError()

    override suspend fun clearUsers(): Unit = throw NotImplementedError()

    override suspend fun refreshCurrentUser(): User? = throw NotImplementedError()
}

private object FixedClock : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(0L)
}
