package com.calypsan.listenup.client.data.remote

import com.calypsan.listenup.api.AuthServiceAuthed
import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.dto.auth.AuthSession as ContractAuthSession
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.repository.AuthRepositoryImpl
import com.calypsan.listenup.client.domain.repository.AuthSession as ClientAuthSession
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

/**
 * Regression coverage for the `RpcAuthRecoveryImpl.refreshAndRebuild` latency-bound fix: the caller
 * that eventually reaches this class ([RpcProxyCache.retryAfterAuthRefresh]) wraps the call in
 * `withTimeoutOrNull(timeout)` so a short caller-declared budget (e.g. 800ms) can't inherit the
 * refresh channel's own much longer internal bound.
 *
 * That is only safe because [AuthRepositoryImpl.refreshAccessToken] — reached via the injected
 * [RefreshAccessToken] seam — runs its OWN rotation on a scope independent of whichever caller
 * invokes it (see that method's KDoc). A caller abandoning its wait therefore only stops WAITING; it
 * never cancels the underlying refresh, which keeps running for whoever else is waiting, or for the
 * next call to find already done. An earlier version of this fix put that scope-independence
 * directly in [RpcAuthRecoveryImpl] instead of in [AuthRepositoryImpl] — which fixed the symptom
 * here but left the identical defect one level down in every OTHER caller of
 * `refreshAccessToken()` ([com.calypsan.listenup.client.playback.CachedAudioTokenProvider] among
 * them). The invariant belongs at the single-flight itself, once, not re-implemented per call site.
 */
class RpcAuthRecoveryImplTest :
    FunSpec({

        fun freshSession(): ContractAuthSession =
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

        test(
            "a caller that times out waiting does not cancel the shared refresh — a concurrent " +
                "caller coalesces onto it and observes the healed session",
        ) {
            runTest {
                var refreshCalls = 0
                val gate = CompletableDeferred<Unit>()
                val public =
                    mock<AuthServicePublic> {
                        everySuspend { refreshSession(any()) } calls {
                            refreshCalls++
                            // Parks — models a refresh RPC slower than any caller's own wait budget.
                            gate.await()
                            AppResult.Success(freshSession())
                        }
                    }
                val clientAuthSession: ClientAuthSession = mock()
                everySuspend { clientAuthSession.currentAuthEpoch() } returns 0L
                everySuspend { clientAuthSession.getRefreshToken() } returns RefreshToken("rt-0")
                everySuspend { clientAuthSession.saveAuthTokens(any(), any(), any(), any(), any()) } returns Unit

                val authRepository =
                    AuthRepositoryImpl(
                        authPublicChannel = RpcChannel.forTest(public, RpcPolicy.Public),
                        authedChannel = RpcChannel.forTest(mock<AuthServiceAuthed>()),
                        authSession = clientAuthSession,
                        // The actual rotation must run here — independent of whichever caller below
                        // becomes leader — so that caller's own withTimeoutOrNull can give up without
                        // aborting it. See AuthRepositoryImpl.refreshAccessToken's KDoc.
                        scope = backgroundScope,
                    )

                val apiClientFactory: ApiClientFactory = mock()
                everySuspend { apiClientFactory.invalidateRequestClientOnly() } returns Unit

                val recovery =
                    RpcAuthRecoveryImpl(
                        authSession = clientAuthSession,
                        refreshAccessToken = { authRepository.refreshAccessToken() },
                        apiClientFactory = apiClientFactory,
                    )

                // Caller A mirrors RpcProxyCache.retryAfterAuthRefresh's own
                // withTimeoutOrNull(timeout) { authRecovery.refreshAndRebuild() } shape and gives up
                // quickly — well short of the refresh RPC's parked `gate.await()`.
                val callerA = withTimeoutOrNull(10.milliseconds) { recovery.refreshAndRebuild() }
                callerA.shouldBeNull()
                refreshCalls shouldBe 1 // the refresh DID start, under caller A's own leadership

                // Caller B arrives while that SAME refresh is still in flight — it must coalesce onto
                // it, not trigger a second refreshSession call. A second call here would present the
                // same not-yet-rotated refresh token twice — the replay-detection hazard
                // AuthRepositoryImpl's own single-flight exists to prevent (see its KDoc).
                val callerB = async { recovery.refreshAndRebuild() }
                runCurrent()
                refreshCalls shouldBe 1 // still just the one call — B did not start a rotation of its own

                // The refresh — abandoned by caller A's own wait, but never cancelled — now completes.
                gate.complete(Unit)

                callerB.await() shouldBe AuthRecoveryOutcome.Refreshed
                refreshCalls shouldBe 1
                verifySuspend(VerifyMode.exactly(1)) { apiClientFactory.invalidateRequestClientOnly() }
            }
        }
    })
