package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.repository.PlaybackPositionRepositoryImpl
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.PendingRegistration
import com.calypsan.listenup.client.domain.repository.PlaybackUpdate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout

private const val ROUND_TRIP_TIMEOUT_SECONDS = 30

/**
 * Tier 3 e2e tests proving the `playback_positions` domain syncs in both directions.
 *
 * Boots the full in-process server + client sync engine via
 * [withClientSyncEngineAgainstServer] and exercises two paths:
 *
 * 1. **Server→client**: [serverPlaybackPositionRepository.recordPosition] publishes a
 *    per-user SSE event. The event crosses the real SSE firehose, the client
 *    [com.calypsan.listenup.client.data.sync.SyncEngine] routes it through the real
 *    [com.calypsan.listenup.client.data.sync.domains.playbackPositionsDomain] handler,
 *    and the row lands in the client's Room database — exactly the round-trip production
 *    performs. A non-zero `revision` on the landed row proves the row arrived through the
 *    domain-specific handler rather than as a stub.
 *
 * 2. **Client→server**: A client-side position write via [PlaybackPositionRepositoryImpl]
 *    enqueues a [com.calypsan.listenup.client.data.sync.PendingOperation] for the
 *    `playback_positions` domain. Calling [com.calypsan.listenup.client.data.sync.PendingOperationQueue.drain]
 *    dispatches the op through the [com.calypsan.listenup.client.data.sync.testing.DirectPlaybackPositionSender]
 *    wired in the harness. The position reaches the server's
 *    [com.calypsan.listenup.server.services.PlaybackPositionRepository] and is asserted
 *    there via [serverPlaybackPositionRepository.getPosition].
 */
class PlaybackPositionEndToEndTest :
    FunSpec({

        test("server recordPosition → SSE → client Room has position with non-zero revision") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                serverPlaybackPositionRepository.recordPosition(
                    userId = "u1",
                    bookId = "book-e2e-1",
                    positionMs = 42_000L,
                    lastPlayedAt = 1_730_000_000_000L,
                    finished = false,
                    playbackSpeed = 1.25f,
                    currentChapterId = null,
                )

                val position =
                    withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                        var row = clientDatabase.playbackPositionDao().get(BookId("book-e2e-1"))
                        while (row == null) {
                            row = clientDatabase.playbackPositionDao().get(BookId("book-e2e-1"))
                        }
                        row
                    }

                position shouldNotBe null
                position.positionMs shouldBe 42_000L
                position.playbackSpeed shouldBe 1.25f
                // Non-zero revision proves the row arrived through the domain sync handler,
                // not as a revision=0 stub from an unrelated bootstrap path.
                position.revision shouldBeGreaterThan 0L
            }
        }

        test("client savePlaybackState → engine queue drain → position on server") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                val authSession = StubAuthSession(userId = "u1")
                val repo =
                    PlaybackPositionRepositoryImpl(
                        dao = clientDatabase.playbackPositionDao(),
                        transactionRunner = RoomTransactionRunner(clientDatabase),
                        pendingQueue = queue,
                        authSession = authSession,
                    )

                // Write a position locally — this enqueues a pending op for the server.
                // The engine's reactive drain subscriber will see the enqueue signal and
                // dispatch the op through DirectPlaybackPositionSender to the server's
                // PlaybackPositionRepository (in-process, no HTTP/RPC round-trip).
                val bookId = BookId("book-e2e-2")
                repo.savePlaybackState(
                    bookId = bookId,
                    update =
                        PlaybackUpdate.PlaybackStarted(
                            positionMs = 99_000L,
                            speed = 1.5f,
                        ),
                )

                // Poll the server DB until the position arrives. The engine's reactive
                // drain picks up the enqueue signal and calls DirectPlaybackPositionSender,
                // which writes to serverPlaybackPositionRepository in-process.
                val serverPosition =
                    withTimeout(ROUND_TRIP_TIMEOUT_SECONDS.seconds) {
                        var row = serverPlaybackPositionRepository.getPosition("u1", "book-e2e-2")
                        while (row == null) {
                            row = serverPlaybackPositionRepository.getPosition("u1", "book-e2e-2")
                        }
                        row
                    }

                serverPosition shouldNotBe null
                serverPosition.positionMs shouldBe 99_000L
                serverPosition.playbackSpeed shouldBe 1.5f
                // revision > 0 confirms the server's sync substrate processed the write.
                serverPosition.revision shouldBeGreaterThan 0L
            }
        }
    })

/**
 * Minimal [AuthSession] stub for test-scoped [PlaybackPositionRepositoryImpl] construction.
 *
 * Returns the supplied [userId] from [getUserId] so the repository can stamp the
 * correct owner on enqueued pending operations. All other session operations are
 * no-ops; the test does not exercise auth state transitions.
 */
private class StubAuthSession(
    private val userId: String,
) : AuthSession {
    private val authenticatedState =
        AuthState.Authenticated(userId = UserId(userId), sessionId = SessionId("stub-session-$userId"))

    override val authState: StateFlow<AuthState> = MutableStateFlow(authenticatedState)

    override suspend fun getUserId(): String = userId

    override suspend fun currentAuthEpoch(): Long = 0L

    override suspend fun saveAuthTokens(
        access: AccessToken,
        refresh: RefreshToken,
        sessionId: String,
        userId: String,
        ifEpoch: Long?,
    ) = Unit

    override suspend fun getAccessToken(): AccessToken? = null

    override suspend fun getRefreshToken(): RefreshToken? = null

    override suspend fun getSessionId(): String? = null

    override suspend fun updateAccessToken(token: AccessToken) = Unit

    override suspend fun clearAuthTokens() = Unit

    override suspend fun clearSessionCredentials() = Unit

    override suspend fun isAuthenticated(): Boolean = true

    override suspend fun initializeAuthState() = Unit

    override suspend fun checkServerStatus(): AuthState = authenticatedState

    override suspend fun refreshOpenRegistration() = Unit

    override suspend fun savePendingRegistration(
        userId: String,
        email: String,
    ) = Unit

    override suspend fun getPendingRegistration(): PendingRegistration? = null

    override suspend fun clearPendingRegistration() = Unit
}
