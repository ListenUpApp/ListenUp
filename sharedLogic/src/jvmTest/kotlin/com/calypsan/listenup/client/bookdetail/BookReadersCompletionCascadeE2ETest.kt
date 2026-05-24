package com.calypsan.listenup.client.bookdetail

import app.cash.turbine.test
import com.calypsan.listenup.api.sync.ActiveSessionSyncPayload
import com.calypsan.listenup.client.data.local.db.ActiveSessionEntity
import com.calypsan.listenup.client.data.local.db.UserProfileEntity
import com.calypsan.listenup.client.data.repository.BookReadersRepositoryImpl
import com.calypsan.listenup.client.data.sync.testing.withClientSyncEngineAgainstServer
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.PendingRegistration
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout

private const val NOW_MS = 1_779_451_200_000L

/**
 * Tier 3 e2e test proving the P3-B completion cascade end-to-end.
 *
 * The test exercises two orthogonal but coupled invariants:
 *
 * 1. **Server-side cascade.** When [PlaybackPositionRepository.recordPosition]
 *    detects a `finished` flip (`false→true`), it atomically calls
 *    [ActiveSessionRepository.deleteForUserBook], soft-deleting the active-session
 *    row and publishing a [com.calypsan.listenup.api.sync.SyncEvent.Deleted] SSE
 *    event. After the call, [serverActiveSessionRepository.getForUser] must return
 *    an empty list — the server-side cascade is proven.
 *
 * 2. **Client-side reactive flow.** [BookReadersRepositoryImpl.observeReadersFor]
 *    is a pure Room [kotlinx.coroutines.flow.Flow] observation over the
 *    `active_sessions` table. When a row is added, the flow emits a
 *    [com.calypsan.listenup.client.domain.readers.BookReaders] with the user in
 *    `currentlyListening`. When the row is deleted, the flow re-emits without that
 *    user — this proves the reactive read-path works end-to-end through the real
 *    Room DAO and the real repository implementation.
 *
 * **Architectural note — cross-user SSE gap.**
 * The per-user SSE firehose delivers events to the authenticated user only.
 * `deleteForUserBook("u2", "bookA")` publishes a `SyncEvent.Deleted` for u2's
 * stream; u1's SSE client does not receive it. Consequently, the client-side Room
 * update that makes u2 disappear from u1's `currentlyListening` cannot happen
 * automatically via the current SSE path. The test therefore seeds the client Room
 * directly (simulating the mechanism that would normally do it — a cross-user
 * subscription or REST pull) and then simulates the deletion via the DAO. The
 * server-side cascade and the client-side reactivity are each proven; their
 * end-to-end wiring through a cross-user channel is noted as a followup item.
 */
class BookReadersCompletionCascadeE2ETest :
    FunSpec({

        test("completion cascade: server deletes active_sessions row and BookReadersRepository reacts") {
            withClientSyncEngineAgainstServer {
                engine.start(currentUserId = "u1")

                // ── Seed server-side: active session row for u2 on bookA ──────────────
                val sessionId = "session-u2-bookA"
                serverActiveSessionRepository.upsert(
                    ActiveSessionSyncPayload(
                        sessionId = sessionId,
                        bookId = "bookA",
                        startedAt = NOW_MS - 60_000L,
                        revision = 0L,
                        updatedAt = NOW_MS,
                        createdAt = NOW_MS,
                        deletedAt = null,
                    ),
                    clientOpId = null,
                    userId = "u2",
                )

                // Confirm the row is on the server before the cascade fires.
                val before = serverActiveSessionRepository.getForUser("u2")
                before shouldHaveSize 1
                before.first().sessionId shouldBe sessionId

                // ── Seed client Room: u2's user profile + active session ──────────────
                // The client Room is seeded directly because the per-user SSE firehose
                // only delivers events to the authenticated user (u1). Cross-user active
                // session propagation is a future work item (see class KDoc).
                clientDatabase.userProfileDao().upsert(
                    UserProfileEntity(
                        id = "u2",
                        displayName = "u2-display",
                        updatedAt = NOW_MS,
                    ),
                )
                clientDatabase.activeSessionDao().upsert(
                    ActiveSessionEntity(
                        sessionId = sessionId,
                        userId = "u2",
                        bookId = "bookA",
                        startedAt = NOW_MS - 60_000L,
                        updatedAt = NOW_MS,
                    ),
                )

                // ── Build repository and observe the flow ─────────────────────────────
                val repo =
                    BookReadersRepositoryImpl(
                        activeSessionDao = clientDatabase.activeSessionDao(),
                        authSession = StubAuthSession(userId = "u1"),
                    )

                repo.observeReadersFor("bookA").test(timeout = 10.seconds) {
                    // First emission: u2 should appear in currentlyListening.
                    val initial = awaitItem()
                    initial.currentlyListening shouldHaveSize 1
                    initial.currentlyListening.first().userId shouldBe "u2"

                    // ── Trigger server-side cascade ──────────────────────────────────
                    // recordPosition with finished=true triggers deleteForUserBook("u2", "bookA"),
                    // which soft-deletes the active_sessions row and publishes a SyncEvent.Deleted.
                    serverPlaybackPositionRepository.recordPosition(
                        userId = "u2",
                        bookId = "bookA",
                        positionMs = 0L,
                        lastPlayedAt = NOW_MS,
                        finished = true,
                        playbackSpeed = 1.0f,
                        currentChapterId = null,
                    )

                    // Assert server-side: the active_sessions row is gone (cascade fired).
                    withTimeout(10.seconds) {
                        var serverSessions = serverActiveSessionRepository.getForUser("u2")
                        while (serverSessions.isNotEmpty()) {
                            serverSessions = serverActiveSessionRepository.getForUser("u2")
                        }
                    }
                    serverActiveSessionRepository.getForUser("u2").shouldBeEmpty()

                    // ── Simulate client-side propagation ─────────────────────────────
                    // The cross-user SSE channel that would notify u1 of u2's session
                    // deletion is not yet implemented (followup). Simulate it by deleting
                    // the Row from the client Room directly — proving the reactive path.
                    clientDatabase.activeSessionDao().deleteBySessionId(sessionId)

                    // Within one Flow emit window, u2 must leave currentlyListening.
                    val after = awaitItem()
                    after.currentlyListening.shouldBeEmpty()

                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    })

/**
 * Minimal [AuthSession] stub for [BookReadersRepositoryImpl] construction in the E2E test.
 *
 * Returns [userId] from [getUserId] so the repository correctly filters the current
 * user out of `currentlyListening`. All other session operations are no-ops.
 */
private class StubAuthSession(
    private val userId: String,
) : AuthSession {
    private val authenticatedState =
        AuthState.Authenticated(userId = UserId(userId), sessionId = SessionId("stub-session-$userId"))

    override val authState: StateFlow<AuthState> = MutableStateFlow(authenticatedState)

    override suspend fun getUserId(): String = userId

    override suspend fun saveAuthTokens(
        access: AccessToken,
        refresh: RefreshToken,
        sessionId: String,
        userId: String,
    ) = Unit

    override suspend fun getAccessToken(): AccessToken? = null

    override suspend fun getRefreshToken(): RefreshToken? = null

    override suspend fun getSessionId(): String? = null

    override suspend fun updateAccessToken(token: AccessToken) = Unit

    override suspend fun clearAuthTokens() = Unit

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
