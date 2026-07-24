package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.AuthServiceAuthed
import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession as ContractAuthSession
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.repository.AuthRepositoryImpl
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.PendingRegistration
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.version.FakeClientIdentity
import com.calypsan.listenup.client.test.http.testMockEngine
import com.calypsan.listenup.core.ServerUrl
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest

private const val PARALLEL_CALLERS = 5
private const val STALE_ACCESS = "stale-access"
private const val FRESH_ACCESS = "fresh-access"

/**
 * In-memory [AuthSession] fake: read-your-writes token state. The bearer
 * plugin's `loadTokens` and the refresh bridge's `saveAuthTokens` both hit
 * this. Members the flow never touches throw, so an unexpected call fails
 * the test loudly instead of silently returning a default.
 */
private class FakeAuthSession : AuthSession {
    var access: AccessToken? = AccessToken(STALE_ACCESS)
    var refresh: RefreshToken? = RefreshToken("rt-0")

    override val authState: StateFlow<AuthState> get() = throw NotImplementedError()

    override suspend fun currentAuthEpoch(): Long = 0L

    override suspend fun saveAuthTokens(
        access: AccessToken,
        refresh: RefreshToken,
        sessionId: String,
        userId: String,
        ifEpoch: Long?,
    ) {
        this.access = access
        this.refresh = refresh
    }

    override suspend fun getAccessToken(): AccessToken? = access

    override suspend fun getRefreshToken(): RefreshToken? = refresh

    override suspend fun updateAccessToken(token: AccessToken) {
        access = token
    }

    override suspend fun clearAuthTokens() {
        access = null
        refresh = null
    }

    override suspend fun clearSessionCredentials() = Unit

    override suspend fun getSessionId(): String? = throw NotImplementedError()

    override suspend fun getUserId(): String? = throw NotImplementedError()

    override suspend fun isAuthenticated(): Boolean = throw NotImplementedError()

    override suspend fun initializeAuthState() = throw NotImplementedError()

    override suspend fun checkServerStatus(): AuthState = throw NotImplementedError()

    override suspend fun refreshOpenRegistration() = throw NotImplementedError()

    override suspend fun savePendingRegistration(
        userId: String,
        email: String,
    ) = throw NotImplementedError()

    override suspend fun getPendingRegistration(): PendingRegistration? = throw NotImplementedError()

    override suspend fun clearPendingRegistration() = throw NotImplementedError()
}

/** Copied shape from RefreshAuthTokensTest.fakeContractSession. */
private fun freshContractSession(): ContractAuthSession =
    ContractAuthSession(
        accessToken = AccessToken(FRESH_ACCESS),
        accessTokenExpiresAt = 0L,
        refreshToken = RefreshToken("rt-1"),
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

/**
 * End-to-end tripwire for single-flight token refresh: N parallel 401s through
 * the REAL client built by [KtorApiClientFactory.createClient] — real bearer
 * plugin, real [refreshAuthTokens] bridge, real [AuthRepositoryImpl]
 * single-flight — must produce exactly ONE rotation RPC, and every caller must
 * complete. This is the regression net for any future HttpClient/auth-plugin
 * restructuring; the cross-client (REST + SSE) race is pinned separately at
 * the invariant layer by AuthRepositoryImplTest's coalescing test, because all
 * refresh paths funnel into the same Koin-singleton AuthRepository
 * (AuthModule.kt `singleOf`).
 */
class TokenRefreshSingleFlightTest :
    FunSpec({

        test("five parallel 401s coalesce into one rotation and all callers complete") {
            runTest(timeout = 30.seconds) {
                val authSession = FakeAuthSession()

                // Counts 401s served; the rotation RPC is gated on it so every
                // caller's refresh attempt is concurrent with the rotation.
                val staleServed = MutableStateFlow(0)
                val refreshRpcCalls = MutableStateFlow(0)

                val public = mock<AuthServicePublic>()
                everySuspend { public.refreshSession(any()) } calls {
                    refreshRpcCalls.update { it + 1 }
                    // Release after the FIRST 401 (not all PARALLEL_CALLERS): gating on the full count
                    // deadlocks when a late caller blocks awaiting this same refresh deferred before it
                    // ever sends its request, so its 401 never arrives and the count never reaches N.
                    staleServed.first { it >= 1 }
                    AppResult.Success(freshContractSession())
                }

                val repo =
                    AuthRepositoryImpl(
                        authPublicChannel = RpcChannel.forTest(public, RpcPolicy.Public),
                        authedChannel = RpcChannel.forTest(mock<AuthServiceAuthed>()),
                        authSession = authSession,
                    )

                val engine =
                    testMockEngine {
                        handle("/protected") { request ->
                            if (request.headers[HttpHeaders.Authorization] == "Bearer $FRESH_ACCESS") {
                                respond(
                                    content = "{}",
                                    status = HttpStatusCode.OK,
                                    headers =
                                        headersOf(
                                            HttpHeaders.ContentType,
                                            ContentType.Application.Json.toString(),
                                        ),
                                )
                            } else {
                                staleServed.update { it + 1 }
                                respondError(HttpStatusCode.Unauthorized)
                            }
                        }
                    }

                val serverConfig = mock<ServerConfig>()
                everySuspend { serverConfig.getActiveUrl() } returns ServerUrl("http://unit.test")
                everySuspend { serverConfig.switchToFallbackUrl() } returns null

                val factory =
                    KtorApiClientFactory(
                        serverConfig = serverConfig,
                        authSession = authSession,
                        // Mirrors NetworkModule's `{ get<AuthRepository>().refreshAccessToken() }`.
                        refreshAccessToken = { repo.refreshAccessToken() },
                        clientIdentity = FakeClientIdentity(),
                        engine = engine,
                    )
                val client = factory.getClient()

                val responses =
                    (1..PARALLEL_CALLERS)
                        .map { async { client.get("/protected") } }
                        .awaitAll()

                // Every caller completed with the retried, freshly-authed request.
                responses.forEach { it.status shouldBe HttpStatusCode.OK }
                // At least one caller 401'd and triggered the single rotation; the rest coalesced onto it or retried through it.
                (staleServed.value >= 1) shouldBe true
                // THE invariant: exactly one rotation despite five concurrent triggers.
                refreshRpcCalls.value shouldBe 1
                // The rotated pair is what's persisted.
                authSession.access shouldBe AccessToken(FRESH_ACCESS)
                authSession.refresh shouldBe RefreshToken("rt-1")

                client.close()
            }
        }
    })
