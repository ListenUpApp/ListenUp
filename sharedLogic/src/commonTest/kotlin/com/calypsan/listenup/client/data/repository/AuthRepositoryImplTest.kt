package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.AuthServiceAuthed
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.ApiClientFactory
import com.calypsan.listenup.client.data.remote.AuthRpcFactory
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ServerConfig
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Drives [AuthRepositoryImpl]'s session-management methods through a fake
 * [AuthRpcFactory] whose `authedService()` returns a mocked
 * [AuthServiceAuthed] — no network. Pins the thin delegation: each repo method
 * forwards to its authed-proxy counterpart and returns its result verbatim.
 */
class AuthRepositoryImplTest :
    FunSpec({

        /** Fake factory: every call resolves to the supplied authed proxy. */
        class FakeAuthRpcFactory(
            private val authed: AuthServiceAuthed,
        ) : AuthRpcFactory(
                apiClientFactory =
                    ApiClientFactory(
                        serverConfig = mock<ServerConfig>(),
                        authSession = mock<AuthSession>(),
                        refreshAccessToken = { error("unused") },
                    ),
                serverConfig = mock<ServerConfig>(),
            ) {
            override suspend fun authedService(): AuthServiceAuthed = authed
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
    })
