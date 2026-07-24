@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.client.playback

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession as ContractAuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.SessionSummary
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.AuthRepository
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.PendingRegistration
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Characterization suite for [CachedAudioTokenProvider]. Pins the current
 * behavior of the shared streaming-token authority under virtual time:
 * init refresh + persistence, the [prepareForPlayback] fast path, mutex
 * serialization of concurrent refreshes (WITHOUT dedup — see test 4), the
 * stored-token grace fallback, the proactive expiry loop, and the
 * [CachedAudioTokenProvider.onUnauthorized] hook.
 *
 * This is characterization, not verification of "correct" behavior — if a
 * test surfaces a real bug, that's a signal for a follow-up plan, not a
 * license to change production behavior here.
 */
class CachedAudioTokenProviderTest :
    FunSpec({

        test("init refresh success caches the token (persistence is the repository's job now)") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val repo =
                    FakeAudioAuthRepository {
                        AppResult.Success(contractSession("t1", clock.now().toEpochMilliseconds() + 60.minutes.inWholeMilliseconds))
                    }
                val storage = FakeStorageAuthSession(stored = null)
                val provider = CachedAudioTokenProvider(storage, repo, backgroundScope, clock)

                testScheduler.runCurrent()

                provider.getToken() shouldBe "t1"
                repo.calls shouldBe 1
                // Persistence now happens inside AuthRepository.refreshAccessToken's single-flight (C1),
                // not in the provider — so the provider no longer writes tokens to storage itself.
                storage.saved shouldBe emptyList()
            }
        }

        test("prepareForPlayback skips refresh while more than 2 minutes remain") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val repo =
                    FakeAudioAuthRepository {
                        AppResult.Success(contractSession("t1", clock.now().toEpochMilliseconds() + 60.minutes.inWholeMilliseconds))
                    }
                val storage = FakeStorageAuthSession(stored = null)
                val provider = CachedAudioTokenProvider(storage, repo, backgroundScope, clock)

                testScheduler.runCurrent()
                val callsAfterInit = repo.calls

                provider.prepareForPlayback()

                repo.calls shouldBe callsAfterInit
            }
        }

        test("prepareForPlayback refreshes when 2 minutes or less remain") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val repo =
                    FakeAudioAuthRepository {
                        AppResult.Success(contractSession("t1", clock.now().toEpochMilliseconds() + 60.seconds.inWholeMilliseconds))
                    }
                val storage = FakeStorageAuthSession(stored = null)
                val provider = CachedAudioTokenProvider(storage, repo, backgroundScope, clock)

                testScheduler.runCurrent()
                val callsAfterInit = repo.calls

                provider.prepareForPlayback()

                repo.calls shouldBe callsAfterInit + 1
            }
        }

        test("concurrent refreshToken calls serialize but each performs its own upstream refresh") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val repo =
                    FakeAudioAuthRepository {
                        delay(1.seconds)
                        AppResult.Success(contractSession("t1", clock.now().toEpochMilliseconds() + 60.minutes.inWholeMilliseconds))
                    }
                val storage = FakeStorageAuthSession(stored = null)
                val provider = CachedAudioTokenProvider(storage, repo, backgroundScope, clock)

                // init's refresh (backgroundScope) parks inside onRefresh's delay(1s)
                // holding the mutex. Drain it fully — advance past 1s but well short
                // of the 5-minute proactive tick — so the mutex is free and calls==1
                // before we fan out the concurrent triggers.
                advanceTimeBy(2.seconds)
                runCurrent()

                // Launch the concurrent triggers in the FOREGROUND test scope, not
                // backgroundScope: advanceUntilIdle() ignores backgroundScope tasks,
                // so triggers parked there would never run. The provider's own scope
                // stays backgroundScope so its infinite proactive loop is cancelled
                // at test end.
                repeat(3) {
                    launch { provider.refreshToken() }
                }
                advanceUntilIdle()

                // The mutex SERIALIZES concurrent refreshes (no overlap)...
                repo.maxConcurrent shouldBe 1
                // ...but does NOT dedupe them: init's call + 3 serialized calls = 4.
                // Single-flight dedup for the upstream rotation RPC lives one layer
                // down, in AuthRepositoryImpl.refreshAccessToken (lines 47-83), not
                // in this class.
                repo.calls shouldBe 4
            }
        }

        test("refresh failure with a stored token falls back to it under a synthetic grace expiry") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val repo = FakeAudioAuthRepository { AppResult.Failure(AuthError.SessionExpired()) }
                val storage = FakeStorageAuthSession(stored = AccessToken("stored"))
                val provider = CachedAudioTokenProvider(storage, repo, backgroundScope, clock)

                testScheduler.runCurrent()

                provider.getToken() shouldBe "stored"
                repo.calls shouldBe 1

                provider.prepareForPlayback()

                // The 50-minute synthetic grace expiry makes the stored token look
                // fresh to the fast path — the Never-Stranded behavior.
                repo.calls shouldBe 1
            }
        }

        test("the proactive loop re-attempts a failed refresh once the grace enters the 10-minute horizon") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val repo = FakeAudioAuthRepository { AppResult.Failure(AuthError.SessionExpired()) }
                val storage = FakeStorageAuthSession(stored = AccessToken("stored"))
                val provider = CachedAudioTokenProvider(storage, repo, backgroundScope, clock)

                testScheduler.runCurrent()
                repo.calls shouldBe 1

                // Grace expiry is t+50min. Cadence ticks at t=5,10,...,40min all see
                // remaining >= 10min (strict `<` comparison) and must NOT refire.
                // The t=45min tick sees remaining=5min < 10min and DOES refire.
                advanceTimeBy(45.minutes)
                runCurrent()

                repo.calls shouldBe 2
                provider.getToken() shouldBe "stored"
            }
        }

        test("proactive loop refreshes a success-path token nearing expiry") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val repo =
                    FakeAudioAuthRepository {
                        AppResult.Success(contractSession("t1", clock.now().toEpochMilliseconds() + 12.minutes.inWholeMilliseconds))
                    }
                val storage = FakeStorageAuthSession(stored = null)
                val provider = CachedAudioTokenProvider(storage, repo, backgroundScope, clock)

                testScheduler.runCurrent()
                repo.calls shouldBe 1

                // At t=5min, remaining = 12-5 = 7min < 10min horizon.
                advanceTimeBy(5.minutes)
                runCurrent()

                repo.calls shouldBe 2
            }
        }

        test("refresh failure with no stored token yields a null token") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val repo = FakeAudioAuthRepository { AppResult.Failure(AuthError.SessionExpired()) }
                val storage = FakeStorageAuthSession(stored = null)
                val provider = CachedAudioTokenProvider(storage, repo, backgroundScope, clock)

                testScheduler.runCurrent()

                provider.getToken() shouldBe null
            }
        }

        test("onUnauthorized triggers a refresh through the shared path") {
            runTest {
                val clock = VirtualClock(testScheduler)
                var callCount = 0
                val repo =
                    FakeAudioAuthRepository {
                        callCount++
                        val token = if (callCount == 1) "t1" else "t2"
                        AppResult.Success(contractSession(token, clock.now().toEpochMilliseconds() + 60.minutes.inWholeMilliseconds))
                    }
                val storage = FakeStorageAuthSession(stored = null)
                val provider = CachedAudioTokenProvider(storage, repo, backgroundScope, clock)

                testScheduler.runCurrent()
                provider.getToken() shouldBe "t1"

                provider.onUnauthorized()
                runCurrent()

                repo.calls shouldBe 2
                provider.getToken() shouldBe "t2"
            }
        }

        test("init does NOT refresh when a stored JWT is comfortably fresh (C11)") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val repo =
                    FakeAudioAuthRepository {
                        AppResult.Success(contractSession("refreshed", clock.now().toEpochMilliseconds() + 60.minutes.inWholeMilliseconds))
                    }
                // exp an hour out — well beyond the 10-minute proactive horizon.
                val storedJwt = jwtWithExp((clock.now().toEpochMilliseconds() + 60.minutes.inWholeMilliseconds) / 1000)
                val storage = FakeStorageAuthSession(stored = AccessToken(storedJwt))
                val provider = CachedAudioTokenProvider(storage, repo, backgroundScope, clock)

                testScheduler.runCurrent()

                // No construction-time rotation; the fresh stored token is served as-is.
                repo.calls shouldBe 0
                provider.getToken() shouldBe storedJwt
            }
        }

        test("init DOES refresh when the stored JWT is inside the refresh horizon (C11)") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val repo =
                    FakeAudioAuthRepository {
                        AppResult.Success(contractSession("t1", clock.now().toEpochMilliseconds() + 60.minutes.inWholeMilliseconds))
                    }
                // exp only a minute out — inside the horizon, so a refresh is warranted.
                val storedJwt = jwtWithExp((clock.now().toEpochMilliseconds() + 60.seconds.inWholeMilliseconds) / 1000)
                val storage = FakeStorageAuthSession(stored = AccessToken(storedJwt))
                val provider = CachedAudioTokenProvider(storage, repo, backgroundScope, clock)

                testScheduler.runCurrent()

                repo.calls shouldBe 1
                provider.getToken() shouldBe "t1"
            }
        }

        // Regression: the iOS image path (KoinHelper.freshAccessToken) now reads getToken() from this
        // shared authority instead of refreshing per-request. A full cover grid reads it ~40 times at
        // once; the old per-request path returned no token for a fraction of those reads (token=MISSING)
        // which then 401'd and left photos stale. getToken() must serve the one cached token to every
        // concurrent reader and never trigger a rotation of its own.
        test("a burst of concurrent token reads (the cover-grid load) serves the cached token with zero extra rotations") {
            runTest {
                val clock = VirtualClock(testScheduler)
                val repo =
                    FakeAudioAuthRepository {
                        AppResult.Success(contractSession("t1", clock.now().toEpochMilliseconds() + 60.minutes.inWholeMilliseconds))
                    }
                val storage = FakeStorageAuthSession(stored = null)
                val provider = CachedAudioTokenProvider(storage, repo, backgroundScope, clock)

                testScheduler.runCurrent()
                val callsAfterInit = repo.calls

                val tokens = List(40) { provider.getToken() }

                tokens.forEach { it shouldBe "t1" }
                repo.calls shouldBe callsAfterInit
            }
        }
    })

/** Builds an (unsigned) JWT whose payload carries the given `exp` (epoch seconds). */
@OptIn(ExperimentalEncodingApi::class)
private fun jwtWithExp(expSeconds: Long): String {
    val enc = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
    val header = enc.encode("""{"alg":"HS256","typ":"JWT"}""".encodeToByteArray())
    val payload = enc.encode("""{"exp":$expSeconds,"sub":"user-1"}""".encodeToByteArray())
    return "$header.$payload.sig"
}

/** Bridges [kotlin.time.Clock] to the test scheduler's virtual time. */
private class VirtualClock(
    private val scheduler: TestCoroutineScheduler,
) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(scheduler.currentTime)
}

/**
 * In-memory [AuthSession] fake. Unreachable members throw so an unexpected
 * call fails the test loudly instead of silently returning a default.
 */
private class FakeStorageAuthSession(
    var stored: AccessToken?,
) : AuthSession {
    val saved = mutableListOf<AccessToken>()

    override val authState: StateFlow<AuthState> get() = throw NotImplementedError()

    override suspend fun currentAuthEpoch(): Long = 0L

    override suspend fun saveAuthTokens(
        access: AccessToken,
        refresh: RefreshToken,
        sessionId: String,
        userId: String,
        ifEpoch: Long?,
    ) {
        saved.add(access)
        stored = access
    }

    override suspend fun getAccessToken(): AccessToken? = stored

    override suspend fun getRefreshToken(): RefreshToken? = throw NotImplementedError()

    override suspend fun getSessionId(): String? = throw NotImplementedError()

    override suspend fun getUserId(): String? = throw NotImplementedError()

    override suspend fun updateAccessToken(token: AccessToken) = throw NotImplementedError()

    override suspend fun clearAuthTokens() = throw NotImplementedError()

    override suspend fun clearSessionCredentials() = throw NotImplementedError()

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

/**
 * In-memory [AuthRepository] fake tracking upstream refresh concurrency.
 * Only [refreshAccessToken] is exercised by [CachedAudioTokenProvider];
 * every other member throws.
 */
private class FakeAudioAuthRepository(
    private val onRefresh: suspend () -> AppResult<ContractAuthSession>,
) : AuthRepository {
    var calls = 0
        private set

    var maxConcurrent = 0
        private set

    private var active = 0

    override suspend fun refreshAccessToken(): AppResult<ContractAuthSession> {
        calls++
        active++
        if (active > maxConcurrent) maxConcurrent = active
        try {
            return onRefresh()
        } finally {
            active--
        }
    }

    override suspend fun login(request: LoginRequest): AppResult<ContractAuthSession> = throw NotImplementedError()

    override suspend fun register(request: RegisterRequest): AppResult<RegisterResult> = throw NotImplementedError()

    override suspend fun setup(request: RegisterRequest): AppResult<ContractAuthSession> = throw NotImplementedError()

    override suspend fun logout(): AppResult<Unit> = throw NotImplementedError()

    override suspend fun listSessions(): AppResult<List<SessionSummary>> = throw NotImplementedError()

    override suspend fun revokeSession(sessionId: SessionId): AppResult<Unit> = throw NotImplementedError()

    override suspend fun logoutAll(): AppResult<Unit> = throw NotImplementedError()
}

/** Builds a minimal [ContractAuthSession] fixture. Shape copied from TokenRefreshSingleFlightTest. */
private fun contractSession(
    token: String,
    expiresAt: Long,
): ContractAuthSession =
    ContractAuthSession(
        accessToken = AccessToken(token),
        accessTokenExpiresAt = expiresAt,
        refreshToken = RefreshToken("rt-$token"),
        refreshTokenExpiresAt = expiresAt,
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
