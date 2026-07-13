package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.AuthServiceAuthed
import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession as ContractAuthSession
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.RpcPolicy
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.domain.repository.AuthSession as ClientAuthSession
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.plugins.websocket.WebSocketException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Drives [AuthRepositoryImpl]'s session-management methods through an authed [RpcChannel] wrapping a
 * mocked [AuthServiceAuthed] — no network. Pins the thin delegation: each repo method forwards to its
 * authed-channel counterpart and returns its result verbatim. Handshake/session calls that don't
 * touch the public channel get an unused Public channel over a bare mock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepositoryImplTest :
    FunSpec({

        fun repository(authed: AuthServiceAuthed): AuthRepositoryImpl =
            AuthRepositoryImpl(
                authPublicChannel = RpcChannel.forTest(mock<AuthServicePublic>(), RpcPolicy.Public),
                authedChannel = RpcChannel.forTest(authed),
                authSession = mock(),
            )

        test("listSessions delegates to the authed service") {
            runTest {
                val authed = mock<AuthServiceAuthed>()
                val summary1 =
                    SessionSummary(
                        id = SessionId("s1"),
                        label = null,
                        createdAt = 0,
                        lastUsedAt = 0,
                        current = true,
                    )
                val summary2 =
                    SessionSummary(
                        id = SessionId("s2"),
                        label = null,
                        createdAt = 0,
                        lastUsedAt = 0,
                        current = false,
                    )
                everySuspend { authed.listSessions() } returns
                    AppResult.Success(listOf(summary1, summary2))

                val result = repository(authed).listSessions()

                result.shouldBeInstanceOf<AppResult.Success<List<SessionSummary>>>()
                result.data shouldBe listOf(summary1, summary2)
                verifySuspend { authed.listSessions() }
            }
        }

        test("revokeSession delegates to the authed service") {
            runTest {
                val authed = mock<AuthServiceAuthed>()
                everySuspend { authed.revokeSession(any()) } returns AppResult.Success(Unit)

                val result = repository(authed).revokeSession(SessionId("s1"))

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                verifySuspend { authed.revokeSession(SessionId("s1")) }
            }
        }

        test("logoutAll delegates to the authed service") {
            runTest {
                val authed = mock<AuthServiceAuthed>()
                everySuspend { authed.logoutAll() } returns AppResult.Success(Unit)

                val result = repository(authed).logoutAll()

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                verifySuspend { authed.logoutAll() }
            }
        }

        test("concurrent refreshes coalesce into a single refreshSession (no replay revoke)") {
            runTest {
                // Gate the leader's refresh in-flight so the second caller must arrive
                // while the first is still running — the exact race that double-rotates
                // the refresh token in production.
                val gate = CompletableDeferred<Unit>()
                var refreshCalls = 0
                val public = mock<AuthServicePublic>()
                everySuspend { public.refreshSession(any()) } calls {
                    refreshCalls++
                    gate.await()
                    AppResult.Failure(AuthError.SessionExpired())
                }
                val authSession = mock<ClientAuthSession>()
                everySuspend { authSession.currentAuthEpoch() } returns 0L
                everySuspend { authSession.getRefreshToken() } returns RefreshToken("rt-0")

                val repo =
                    AuthRepositoryImpl(
                        authPublicChannel = RpcChannel.forTest(public, RpcPolicy.Public),
                        authedChannel = RpcChannel.forTest(mock<AuthServiceAuthed>()),
                        authSession = authSession,
                    )

                val first = async { repo.refreshAccessToken() }
                val second = async { repo.refreshAccessToken() }
                runCurrent() // leader enters refreshSession (suspends on gate); follower coalesces
                gate.complete(Unit)

                first.await()
                second.await()

                // Exactly one rotation, even though two callers asked concurrently.
                refreshCalls shouldBe 1
            }
        }

        test("a successful refresh persists the rotated tokens inside the single-flight, epoch-guarded (C1/C8)") {
            runTest {
                val session =
                    ContractAuthSession(
                        accessToken = AccessToken("fresh-access"),
                        accessTokenExpiresAt = 0L,
                        refreshToken = RefreshToken("fresh-refresh"),
                        refreshTokenExpiresAt = 0L,
                        sessionId = SessionId("session-1"),
                        user =
                            User(
                                id = UserId("user-1"),
                                email = "alice@example.com",
                                displayName = "Alice",
                                role = UserRole.MEMBER,
                                status = UserStatus.ACTIVE,
                                createdAt = 0L,
                            ),
                    )
                val public = mock<AuthServicePublic>()
                everySuspend { public.refreshSession(any()) } returns AppResult.Success(session)
                val authSession = mock<ClientAuthSession>()
                everySuspend { authSession.currentAuthEpoch() } returns 7L
                everySuspend { authSession.getRefreshToken() } returns RefreshToken("rt-0")
                everySuspend { authSession.saveAuthTokens(any(), any(), any(), any(), any()) } returns Unit
                val repo =
                    AuthRepositoryImpl(
                        authPublicChannel = RpcChannel.forTest(public, RpcPolicy.Public),
                        authedChannel = RpcChannel.forTest(mock<AuthServiceAuthed>()),
                        authSession = authSession,
                    )

                repo.refreshAccessToken().shouldBeInstanceOf<AppResult.Success<*>>()

                // Persisted inside refreshAccessToken with the epoch captured at its start (C1/C8).
                verifySuspend {
                    authSession.saveAuthTokens(
                        access = AccessToken("fresh-access"),
                        refresh = RefreshToken("fresh-refresh"),
                        sessionId = "session-1",
                        userId = "user-1",
                        ifEpoch = 7L,
                    )
                }
            }
        }

        test("a leader whose token read throws wakes coalesced followers with a Failure (never hangs)") {
            runTest {
                // The leader's getRefreshToken() throws a non-cancellation fault AFTER a follower has
                // coalesced onto its in-flight deferred. Before the fix the leader never completed the
                // deferred, so the follower awaited it forever (runTest would time out). The leader
                // must ALWAYS complete its deferred.
                val readGate = CompletableDeferred<Unit>()
                val authSession = mock<ClientAuthSession>()
                everySuspend { authSession.currentAuthEpoch() } returns 0L
                everySuspend { authSession.getRefreshToken() } calls {
                    readGate.await()
                    throw RuntimeException("secure storage read failed")
                }
                val repo =
                    AuthRepositoryImpl(
                        authPublicChannel = RpcChannel.forTest(mock<AuthServicePublic>(), RpcPolicy.Public),
                        authedChannel = RpcChannel.forTest(mock<AuthServiceAuthed>()),
                        authSession = authSession,
                    )

                val leader = async { runCatching { repo.refreshAccessToken() } }
                val follower = async { repo.refreshAccessToken() }
                runCurrent() // leader registers + suspends on the token read; follower coalesces + awaits it
                readGate.complete(Unit) // the leader's read now throws

                // The follower WAKES with a Failure instead of hanging on a never-completed deferred.
                follower.await().shouldBeInstanceOf<AppResult.Failure>()
                // The leader's own call still surfaced the throw (rethrown after completing the deferred).
                leader.await().isFailure shouldBe true
            }
        }

        // Regression for the trigger bug (spec §6.1): a refresh failure that surfaces as a THROW
        // must become a typed AuthError via ErrorMapper — not collapse to InternalError, which
        // defeats refreshAuthTokens' clear-on-typed-auth-error branch and loops forever.
        test("a thrown WS-handshake-401 during refresh maps to AuthError.SessionExpired") {
            runTest {
                val public = mock<AuthServicePublic>()
                everySuspend { public.refreshSession(any()) } throws
                    WebSocketException("Handshake exception, expected status code 101 but was 401")
                val authSession = mock<ClientAuthSession>()
                everySuspend { authSession.currentAuthEpoch() } returns 0L
                everySuspend { authSession.getRefreshToken() } returns RefreshToken("rt-0")
                val repo =
                    AuthRepositoryImpl(
                        authPublicChannel = RpcChannel.forTest(public, RpcPolicy.Public),
                        authedChannel = RpcChannel.forTest(mock<AuthServiceAuthed>()),
                        authSession = authSession,
                    )

                val result = repo.refreshAccessToken()

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.shouldBeInstanceOf<AuthError.SessionExpired>()
            }
        }

        test("a thrown IO failure during refresh maps to NetworkUnavailable (auth state preservable)") {
            runTest {
                val public = mock<AuthServicePublic>()
                everySuspend { public.refreshSession(any()) } throws
                    kotlinx.io.IOException("connection refused")
                val authSession = mock<ClientAuthSession>()
                everySuspend { authSession.currentAuthEpoch() } returns 0L
                everySuspend { authSession.getRefreshToken() } returns RefreshToken("rt-0")
                val repo =
                    AuthRepositoryImpl(
                        authPublicChannel = RpcChannel.forTest(public, RpcPolicy.Public),
                        authedChannel = RpcChannel.forTest(mock<AuthServiceAuthed>()),
                        authSession = authSession,
                    )

                val result = repo.refreshAccessToken()

                result.shouldBeInstanceOf<AppResult.Failure>()
                result.error.shouldBeInstanceOf<TransportError.NetworkUnavailable>()
            }
        }
    })
