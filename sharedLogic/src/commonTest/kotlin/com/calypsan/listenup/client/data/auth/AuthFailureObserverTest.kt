package com.calypsan.listenup.client.data.auth

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.client.data.repository.AuthSessionStore
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.RegistrationPolicyStream
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.core.SecureStorage
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Tests for [AuthFailureObserver] — the single place that converts a
 * session-invalidating [AuthError] surfaced on the [ErrorBus] into a soft logout,
 * driving navigation back to the login screen.
 *
 * The observer collects a hot [kotlinx.coroutines.flow.SharedFlow], so the collector
 * is launched on an [UnconfinedTestDispatcher] — it subscribes eagerly, before the
 * test emits, and processes each emission synchronously.
 */
class AuthFailureObserverTest :
    FunSpec({
        fun authenticatedStore(): AuthSessionStore {
            val storage = mock<SecureStorage>()
            everySuspend { storage.save(any(), any()) } returns Unit
            everySuspend { storage.delete(any()) } returns Unit
            // clearAuthTokens reads KEY_OPEN_REGISTRATION when routing to NeedsLogin;
            // clearSessionCredentials reads user_id to lapse into SessionLapsed.
            // Mokkery's last matching stub wins, so the specific stub goes second.
            everySuspend { storage.read(any()) } returns null
            everySuspend { storage.read("user_id") } returns "user"
            val silentPolicyStream =
                object : RegistrationPolicyStream {
                    override fun streamPolicy() = emptyFlow<com.calypsan.listenup.api.dto.auth.RegistrationPolicy>()
                }
            return AuthSessionStore(
                storage,
                mock<ServerConfig>(),
                mock<InstanceRepository>(),
                lazyOf(silentPolicyStream),
                CoroutineScope(Dispatchers.Unconfined),
            )
        }

        test("SessionExpired while Authenticated lands in SessionLapsed — never the login wall") {
            runTest {
                val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
                try {
                    val errorBus = ErrorBus()
                    val store = authenticatedStore()
                    store.saveAuthTokens(AccessToken("a"), RefreshToken("r"), "session", "user")
                    store.authState.value.shouldBeInstanceOf<AuthState.Authenticated>()

                    AuthFailureObserver(errorBus, store, scope)
                    errorBus.emit(AuthError.SessionExpired())

                    store.authState.value shouldBe AuthState.SessionLapsed(UserId("user"))
                } finally {
                    scope.cancel()
                }
            }
        }

        test("ServerInstanceChanged while Authenticated keeps the full sign-out wall (NeedsLogin)") {
            runTest {
                val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
                try {
                    val errorBus = ErrorBus()
                    val store = authenticatedStore()
                    store.saveAuthTokens(AccessToken("a"), RefreshToken("r"), "session", "user")

                    AuthFailureObserver(errorBus, store, scope)
                    errorBus.emit(AuthError.ServerInstanceChanged())

                    store.authState.value.shouldBeInstanceOf<AuthState.NeedsLogin>()
                } finally {
                    scope.cancel()
                }
            }
        }

        test("a repeat auth error while already SessionLapsed is a no-op (natural dedup at the reactor)") {
            runTest {
                val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
                try {
                    val errorBus = ErrorBus()
                    var softClears = 0
                    val session =
                        FakeAuthSession(
                            authState = AuthState.SessionLapsed(UserId("u1")),
                            onClearSessionCredentials = { softClears++ },
                        )

                    AuthFailureObserver(errorBus, session, scope)
                    errorBus.emit(AuthError.SessionExpired())

                    softClears shouldBe 0
                } finally {
                    scope.cancel()
                }
            }
        }

        test("non-auth error while Authenticated does not log the user out") {
            runTest {
                val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
                try {
                    val errorBus = ErrorBus()
                    val store = authenticatedStore()
                    store.saveAuthTokens(AccessToken("a"), RefreshToken("r"), "session", "user")

                    AuthFailureObserver(errorBus, store, scope)
                    errorBus.emit(TransportError.Server5xx(statusCode = 500))

                    store.authState.value.shouldBeInstanceOf<AuthState.Authenticated>()
                } finally {
                    scope.cancel()
                }
            }
        }

        test("session-invalidating error while not Authenticated is ignored") {
            runTest {
                val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
                try {
                    val errorBus = ErrorBus()
                    val store = authenticatedStore()
                    // Never authenticated — store stays in its initial state.
                    val before = store.authState.value

                    AuthFailureObserver(errorBus, store, scope)
                    errorBus.emit(AuthError.SessionExpired())

                    store.authState.value shouldBe before
                } finally {
                    scope.cancel()
                }
            }
        }

        // Regression: a throwing clearSessionCredentials (e.g. a locked Keychain) must not kill the
        // collector. Before the guard, the first throw terminated the collector (soft-logout
        // permanently broke) and, on Kotlin/Native, the unhandled exception killed the process.
        test("a throwing clearSessionCredentials does not kill the observer; a later error still soft-clears") {
            runTest {
                val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
                try {
                    val errorBus = ErrorBus()
                    var clearCalls = 0
                    val session =
                        FakeAuthSession(
                            authState = AuthState.Authenticated(UserId("u1"), SessionId("session")),
                            onClearSessionCredentials = {
                                clearCalls++
                                if (clearCalls == 1) throw RuntimeException("Keychain locked on first attempt")
                            },
                        )

                    AuthFailureObserver(errorBus, session, scope)
                    errorBus.emit(AuthError.SessionExpired()) // 1st: clearSessionCredentials throws → guard must absorb
                    errorBus.emit(AuthError.SessionExpired()) // 2nd: only reached if the collector survived

                    // Reaching the second clear proves the collector kept running past the first throw.
                    clearCalls shouldBe 2
                } finally {
                    scope.cancel()
                }
            }
        }
    })
