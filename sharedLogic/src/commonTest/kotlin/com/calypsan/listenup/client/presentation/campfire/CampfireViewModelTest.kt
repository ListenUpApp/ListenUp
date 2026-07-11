@file:OptIn(ExperimentalTime::class, ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.presentation.campfire

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.campfire.CampfireAnchor
import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireFrame
import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.CampfireInvitableUser
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.api.dto.campfire.CampfireSnapshot
import com.calypsan.listenup.api.dto.campfire.OpenCampfireSummary
import com.calypsan.listenup.api.dto.campfire.PlaybackCommand
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.CampfireError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.client.campfire.CampfireSessionController
import com.calypsan.listenup.client.campfire.CampfireTransport
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.client.test.fake.FakePlaybackController
import com.calypsan.listenup.client.test.fake.FakePlaybackManager
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Tests for [CampfireViewModel] (campfire implementation plan, Task 9). Drives a real
 * [CampfireSessionController] (backed by a hand-written [FakeCampfireTransport] and the shared
 * [FakePlaybackManager]/[FakePlaybackController] fakes) so the VM's state mapping and delegation
 * are exercised end-to-end, not against a mocked controller.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CampfireViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        val sessionId = CampfireId("cf-1")

        fun anchor(
            positionMs: Long = 0L,
            isPlaying: Boolean = false,
        ) = CampfireAnchor(positionMs, 0L, 1.0f, isPlaying, 0L)

        fun snapshot(id: CampfireId = sessionId) =
            CampfireSnapshot(
                id = id,
                bookId = "book-1",
                settings = CampfireSettings(controlMode = CampfireControlMode.EVERYONE, inviteOnly = false),
                anchor = anchor(),
                members = emptyList(),
                hostUserId = "host-1",
                recentChat = emptyList(),
                yourPositionMs = null,
                spoilerAhead = false,
            )

        class Fixture {
            val transport = FakeCampfireTransport()
            val errorBus = ErrorBus()

            fun build(scope: TestScope): CampfireViewModel {
                val controller =
                    CampfireSessionController(
                        transport = transport,
                        playbackManager = FakePlaybackManager(),
                        playbackController = FakePlaybackController(),
                        userRepository = FakeUserRepository("self-1"),
                        scope = scope.backgroundScope,
                        clock = FixedClock,
                    )
                return CampfireViewModel(controller = controller, transport = transport, errorBus = errorBus)
            }
        }

        fun TestScope.keepStateHot(viewModel: CampfireViewModel) {
            backgroundScope.launch { viewModel.state.collect { } }
        }

        /**
         * Collects [ErrorBus.errors] into a list for the duration of [body], then cancels the
         * collector. A plain `launch` (not `backgroundScope.launch`) — the latter's child
         * coroutine isn't reliably pumped to its suspension point by `advanceUntilIdle()` for a
         * `SharedFlow` collector in this harness, so assertions on captured emissions raced and
         * silently saw nothing; explicit cancellation avoids the "test finished with active jobs"
         * failure a never-completing plain `launch` would otherwise cause.
         */
        suspend fun TestScope.collectErrors(
            bus: ErrorBus,
            body: suspend (emitted: List<AppError>) -> Unit,
        ) {
            val emitted = mutableListOf<AppError>()
            val job = launch { bus.errors.collect { emitted += it } }
            advanceUntilIdle()
            try {
                body(emitted)
            } finally {
                job.cancel()
            }
        }

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        test("initial state is Idle before any join") {
            runTest {
                val viewModel = Fixture().build(this)
                viewModel.state.value.shouldBeInstanceOf<CampfireScreenUiState.Idle>()
            }
        }

        test("join delegates to the controller and reaches Active") {
            runTest {
                val fixture = Fixture()
                fixture.transport.joinResult = AppResult.Success(snapshot())
                val viewModel = fixture.build(this).also { keepStateHot(it) }

                viewModel.join(sessionId)
                advanceUntilIdle()

                val active = viewModel.state.value.shouldBeInstanceOf<CampfireScreenUiState.Active>()
                active.sessionId shouldBe sessionId
                active.bookId shouldBe "book-1"
            }
        }

        test("createCampfire success joins the created session") {
            runTest {
                val fixture = Fixture()
                val createdId = CampfireId("cf-created")
                fixture.transport.createResult = AppResult.Success(snapshot(id = createdId))
                fixture.transport.joinResult = AppResult.Success(snapshot(id = createdId))
                val viewModel = fixture.build(this).also { keepStateHot(it) }

                viewModel.createCampfire("book-1", CampfireSettings(controlMode = CampfireControlMode.EVERYONE, inviteOnly = false))
                advanceUntilIdle()

                fixture.transport.joinCalls shouldBe listOf(createdId)
                viewModel.createState.value.shouldBeInstanceOf<CampfireCreateUiState.Idle>()
                val active = viewModel.state.value.shouldBeInstanceOf<CampfireScreenUiState.Active>()
                active.sessionId shouldBe createdId
            }
        }

        test("createCampfire failure emits to the error bus and carries the typed AppError") {
            runTest {
                val fixture = Fixture()
                val error = CampfireError.BookAccessDenied()
                fixture.transport.createResult = AppResult.Failure(error)
                val viewModel = fixture.build(this)

                collectErrors(fixture.errorBus) { emitted ->
                    viewModel.createCampfire("book-1", CampfireSettings(controlMode = CampfireControlMode.EVERYONE, inviteOnly = false))
                    advanceUntilIdle()

                    emitted shouldBe listOf(error)
                    val errorState = viewModel.createState.value.shouldBeInstanceOf<CampfireCreateUiState.Error>()
                    errorState.error shouldBe error
                }
            }
        }

        test("listInvitableUsers populates inviteState with the access-filtered candidates") {
            runTest {
                val fixture = Fixture()
                fixture.transport.invitableUsersResult =
                    AppResult.Success(listOf(CampfireInvitableUser(userId = "u2", displayName = "Bob")))
                val viewModel = fixture.build(this)

                viewModel.listInvitableUsers("book-1")
                advanceUntilIdle()

                val ready = viewModel.inviteState.value.shouldBeInstanceOf<CampfireInviteUiState.Ready>()
                ready.users.map { it.userId } shouldBe listOf("u2")
            }
        }

        test("listInvitableUsers failure emits to the error bus and carries the typed AppError") {
            runTest {
                val fixture = Fixture()
                val error = TransportError.NetworkUnavailable()
                fixture.transport.invitableUsersResult = AppResult.Failure(error)
                val viewModel = fixture.build(this)

                collectErrors(fixture.errorBus) { emitted ->
                    viewModel.listInvitableUsers("book-1")
                    advanceUntilIdle()

                    emitted shouldBe listOf(error)
                    viewModel.inviteState.value.shouldBeInstanceOf<CampfireInviteUiState.Error>()
                }
            }
        }

        test("pause without control forwards ControlDenied as a screen event") {
            runTest {
                val fixture = Fixture()
                fixture.transport.joinResult =
                    AppResult.Success(
                        snapshot().copy(
                            settings = CampfireSettings(controlMode = CampfireControlMode.HOST_ONLY, inviteOnly = false),
                            hostUserId = "someone-else",
                        ),
                    )
                val viewModel = fixture.build(this)
                viewModel.join(sessionId)
                advanceUntilIdle()

                viewModel.events.test {
                    viewModel.pause()
                    advanceUntilIdle()
                    awaitItem() shouldBe CampfireScreenEvent.ControlDenied
                }
            }
        }

        test("leave delegates to the controller and returns state to Idle") {
            runTest {
                val fixture = Fixture()
                fixture.transport.joinResult = AppResult.Success(snapshot())
                val viewModel = fixture.build(this).also { keepStateHot(it) }
                viewModel.join(sessionId)
                advanceUntilIdle()

                viewModel.leave()
                advanceUntilIdle()

                viewModel.state.value.shouldBeInstanceOf<CampfireScreenUiState.Idle>()
                fixture.transport.leaveCalls shouldBe listOf(sessionId)
            }
        }
    })

/** Fixed [Clock] — this VM's tests don't exercise drift-loop timing, only state mapping/delegation. */
private object FixedClock : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(0L)
}

/** In-memory [CampfireTransport] fake — records every call, replays a hot frame flow for [observeSession]. */
private class FakeCampfireTransport : CampfireTransport {
    var createResult: AppResult<CampfireSnapshot> = AppResult.Failure(CampfireError.CampfireNotFound())
    var joinResult: AppResult<CampfireSnapshot> = AppResult.Failure(CampfireError.CampfireNotFound())
    var invitableUsersResult: AppResult<List<CampfireInvitableUser>> = AppResult.Success(emptyList())
    val joinCalls = mutableListOf<CampfireId>()
    val leaveCalls = mutableListOf<CampfireId>()

    private val frameFlow = MutableSharedFlow<RpcEvent<CampfireFrame>>(extraBufferCapacity = 64)

    override suspend fun createSession(
        bookId: String,
        settings: CampfireSettings,
    ): AppResult<CampfireSnapshot> = createResult

    override suspend fun joinSession(sessionId: CampfireId): AppResult<CampfireSnapshot> {
        joinCalls += sessionId
        return joinResult
    }

    override suspend fun leaveSession(sessionId: CampfireId): AppResult<Unit> {
        leaveCalls += sessionId
        return AppResult.Success(Unit)
    }

    override suspend fun endSession(sessionId: CampfireId): AppResult<Unit> = throw NotImplementedError()

    override suspend fun transferHost(
        sessionId: CampfireId,
        toUserId: String,
    ): AppResult<Unit> = throw NotImplementedError()

    override suspend fun setControlMode(
        sessionId: CampfireId,
        mode: CampfireControlMode,
    ): AppResult<Unit> = throw NotImplementedError()

    override fun observeSession(sessionId: CampfireId): Flow<RpcEvent<CampfireFrame>> = frameFlow

    override suspend fun sendCommand(
        sessionId: CampfireId,
        command: PlaybackCommand,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun sendChat(
        sessionId: CampfireId,
        text: String,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun sendReaction(
        sessionId: CampfireId,
        emoji: String,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun listOpenSessions(): AppResult<List<OpenCampfireSummary>> =
        AppResult.Success(emptyList())

    override suspend fun listInvitableUsers(bookId: String): AppResult<List<CampfireInvitableUser>> = invitableUsersResult
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
