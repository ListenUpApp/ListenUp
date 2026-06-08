package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.AuthServiceAuthed
import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.AuthRpcFactory
import com.calypsan.listenup.client.domain.repository.AuthSession as ClientAuthSession
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Drives [AuthRepositoryImpl]'s session-management methods through a fake
 * [AuthRpcFactory] whose `authedService()` returns a mocked
 * [AuthServiceAuthed] — no network. Pins the thin delegation: each repo method
 * forwards to its authed-proxy counterpart and returns its result verbatim.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AuthRepositoryImplTest :
    FunSpec({

        /** Fake factory: every call resolves to the supplied authed proxy. */
        class FakeAuthRpcFactory(
            private val authed: AuthServiceAuthed,
        ) : AuthRpcFactory {
            override suspend fun authedService(): AuthServiceAuthed = authed

            override suspend fun publicService() = throw NotImplementedError()

            override suspend fun invalidate() = Unit
        }

        fun repository(authed: AuthServiceAuthed): AuthRepositoryImpl = AuthRepositoryImpl(rpc = FakeAuthRpcFactory(authed), authSession = mock())

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
                everySuspend { authSession.getRefreshToken() } returns RefreshToken("rt-0")

                val repo =
                    AuthRepositoryImpl(
                        rpc =
                            object : AuthRpcFactory {
                                override suspend fun publicService(): AuthServicePublic = public

                                override suspend fun authedService(): AuthServiceAuthed = throw NotImplementedError()

                                override suspend fun invalidate() = Unit
                            },
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
    })
