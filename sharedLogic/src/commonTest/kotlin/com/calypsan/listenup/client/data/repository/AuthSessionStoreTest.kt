package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.core.SecureStorage
import com.calypsan.listenup.core.ServerUrl
import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.RegistrationPolicyStream
import com.calypsan.listenup.client.domain.repository.ServerConfig
import app.cash.turbine.test
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

private fun createTestServerInfo(
    setupRequired: Boolean,
    registrationPolicy: RegistrationPolicy = RegistrationPolicy.OPEN,
): ServerInfo =
    ServerInfo(
        name = "Test Instance",
        version = "1.0.0",
        apiVersion = "v1",
        setupRequired = setupRequired,
        registrationPolicy = registrationPolicy,
        instanceId = "test-instance",
    )

private fun createMockStorage(): SecureStorage = mock<SecureStorage>()

private fun createMockServerConfig(): ServerConfig = mock<ServerConfig>()

private fun createMockInstanceRepository(): InstanceRepository = mock<InstanceRepository>()

/** A policy stream backed by a supplied flow; defaults to a silent stream (no live updates). */
private class FakePolicyStream(
    private val flow: Flow<RegistrationPolicy> = emptyFlow(),
) : RegistrationPolicyStream {
    override fun streamPolicy(): Flow<RegistrationPolicy> = flow
}

private fun createStore(
    storage: SecureStorage = createMockStorage(),
    serverConfig: ServerConfig = createMockServerConfig(),
    instanceRepository: InstanceRepository = createMockInstanceRepository(),
    policyStream: RegistrationPolicyStream = FakePolicyStream(),
    scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined),
): AuthSessionStore = AuthSessionStore(storage, serverConfig, instanceRepository, lazyOf(policyStream), scope)

/**
 * Tests for [AuthSessionStore] — the auth slice extracted from
 * `SettingsRepositoryImpl`. Mocks `SecureStorage`, `ServerConfig`, and
 * `InstanceRepository`; everything auth-shaped is in scope here.
 */
class AuthSessionStoreTest :
    FunSpec({

        test("initial auth state is Initializing") {
            runTest {
                val store = createStore()
                // Initializing prevents flash of wrong screen on app startup.
                store.authState.value.shouldBeInstanceOf<AuthState.Initializing>()
            }
        }

        test("saveAuthTokens stores all tokens and updates state to Authenticated") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.save(any(), any()) } returns Unit
                val store = createStore(storage = storage)

                store.saveAuthTokens(
                    AccessToken("access123"),
                    RefreshToken("refresh456"),
                    "session789",
                    "user001",
                )

                verifySuspend { storage.save("access_token", "access123") }
                verifySuspend { storage.save("refresh_token", "refresh456") }
                verifySuspend { storage.save("session_id", "session789") }
                verifySuspend { storage.save("user_id", "user001") }

                val state = store.authState.value.shouldBeInstanceOf<AuthState.Authenticated>()
                state.userId.value shouldBe "user001"
                state.sessionId.value shouldBe "session789"
            }
        }

        test("getAccessToken returns stored token") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.read("access_token") } returns "access123"
                val store = createStore(storage = storage)

                store.getAccessToken() shouldBe AccessToken("access123")
            }
        }

        test("getRefreshToken returns stored token") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.read("refresh_token") } returns "refresh456"
                val store = createStore(storage = storage)

                store.getRefreshToken() shouldBe RefreshToken("refresh456")
            }
        }

        test("getSessionId returns stored session ID") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.read("session_id") } returns "session789"
                val store = createStore(storage = storage)

                store.getSessionId() shouldBe "session789"
            }
        }

        test("getUserId returns stored user ID") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.read("user_id") } returns "user001"
                val store = createStore(storage = storage)

                store.getUserId() shouldBe "user001"
            }
        }

        test("updateAccessToken updates only access token") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.save("access_token", "newAccess") } returns Unit
                val store = createStore(storage = storage)

                store.updateAccessToken(AccessToken("newAccess"))

                verifySuspend { storage.save("access_token", "newAccess") }
            }
        }

        test("clearAuthTokens removes all auth data and updates state to NeedsLogin") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.delete(any()) } returns Unit
                everySuspend { storage.read("open_registration") } returns null
                val store = createStore(storage = storage)

                store.clearAuthTokens()

                verifySuspend { storage.delete("access_token") }
                verifySuspend { storage.delete("refresh_token") }
                verifySuspend { storage.delete("session_id") }
                verifySuspend { storage.delete("user_id") }

                store.authState.value.shouldBeInstanceOf<AuthState.NeedsLogin>()
            }
        }

        test("clearPendingRegistration removes pending data and returns state to NeedsLogin") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.delete(any()) } returns Unit
                everySuspend { storage.read("open_registration") } returns null
                everySuspend { storage.save(any(), any()) } returns Unit
                val store = createStore(storage = storage)
                // Put the store into PendingApproval first.
                store.savePendingRegistration(userId = "user-1", email = "reader@example.com")
                store.authState.value.shouldBeInstanceOf<AuthState.PendingApproval>()

                store.clearPendingRegistration()

                verifySuspend { storage.delete("pending_user_id") }
                verifySuspend { storage.delete("pending_email") }
                // Leaving the pending state must route the user somewhere — back to login — not
                // strand them on the pending screen (the Cancel-button bug).
                store.authState.value.shouldBeInstanceOf<AuthState.NeedsLogin>()
            }
        }

        test("isAuthenticated returns true when access token exists") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.read("access_token") } returns "access123"
                val store = createStore(storage = storage)

                store.isAuthenticated() shouldBe true
            }
        }

        test("isAuthenticated returns false when access token missing") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.read("access_token") } returns null
                val store = createStore(storage = storage)

                store.isAuthenticated() shouldBe false
            }
        }

        test("initializeAuthState sets NeedsServerUrl when no URL configured") {
            runTest {
                val serverConfig = createMockServerConfig()
                everySuspend { serverConfig.getServerUrl() } returns null
                val store = createStore(serverConfig = serverConfig)

                store.initializeAuthState()

                store.authState.value.shouldBeInstanceOf<AuthState.NeedsServerUrl>()
            }
        }

        test("initializeAuthState sets Authenticated when tokens and IDs present") {
            runTest {
                val storage = createMockStorage()
                val serverConfig = createMockServerConfig()
                everySuspend { serverConfig.getServerUrl() } returns ServerUrl("https://api.example.com")
                everySuspend { storage.read("access_token") } returns "access123"
                everySuspend { storage.read("user_id") } returns "user001"
                everySuspend { storage.read("session_id") } returns "session789"
                val store = createStore(storage = storage, serverConfig = serverConfig)

                store.initializeAuthState()

                val state = store.authState.value.shouldBeInstanceOf<AuthState.Authenticated>()
                state.userId.value shouldBe "user001"
                state.sessionId.value shouldBe "session789"
            }
        }

        test("initializeAuthState lands on NeedsLogin when URL present but no tokens or pending") {
            runTest {
                val storage = createMockStorage()
                val serverConfig = createMockServerConfig()
                everySuspend { serverConfig.getServerUrl() } returns ServerUrl("https://api.example.com")
                everySuspend { storage.read("access_token") } returns null
                everySuspend { storage.read("user_id") } returns null
                everySuspend { storage.read("session_id") } returns null
                everySuspend { storage.read("pending_user_id") } returns null
                everySuspend { storage.read("open_registration") } returns null
                everySuspend { storage.read("setup_required") } returns null
                val store = createStore(storage = storage, serverConfig = serverConfig)

                store.initializeAuthState()

                store.authState.value.shouldBeInstanceOf<AuthState.NeedsLogin>()
            }
        }

        test("checkServerStatus sets NeedsSetup when server requires setup") {
            runTest {
                val storage = createMockStorage()
                val instanceRepository = createMockInstanceRepository()
                everySuspend { storage.save(any(), any()) } returns Unit
                everySuspend { instanceRepository.getServerInfo(forceRefresh = true) } returns
                    AppResult.Success(createTestServerInfo(setupRequired = true))
                val store = createStore(storage = storage, instanceRepository = instanceRepository)

                store.checkServerStatus()

                store.authState.value.shouldBeInstanceOf<AuthState.NeedsSetup>()
            }
        }

        test("initializeAuthState returns NeedsSetup when setup was required on the last server check") {
            runTest {
                // Regression: a relaunch runs the offline deriveAuthState (no network), which must honour
                // the cached setupRequired flag. Otherwise a fresh server (no admin yet) resolves to the
                // Login screen, whose "Create Account" leads to the approval-gated request flow with no
                // path back to admin setup — a fresh-server dead-end.
                val storage = createMockStorage()
                val serverConfig = createMockServerConfig()
                everySuspend { serverConfig.getServerUrl() } returns ServerUrl("http://test:8088")
                everySuspend { storage.read("access_token") } returns null
                everySuspend { storage.read("user_id") } returns null
                everySuspend { storage.read("session_id") } returns null
                everySuspend { storage.read("pending_user_id") } returns null
                everySuspend { storage.read("setup_required") } returns "true"
                val store = createStore(storage = storage, serverConfig = serverConfig)

                store.initializeAuthState()

                store.authState.value.shouldBeInstanceOf<AuthState.NeedsSetup>()
            }
        }

        test("checkServerStatus sets NeedsLogin on network failure without clearing URL") {
            runTest {
                val storage = createMockStorage()
                val instanceRepository = createMockInstanceRepository()
                everySuspend { storage.read("open_registration") } returns null
                everySuspend { instanceRepository.getServerInfo(forceRefresh = true) } returns
                    Failure(Exception("Network error"))
                val store = createStore(storage = storage, instanceRepository = instanceRepository)

                store.checkServerStatus()

                // Stays in NeedsLogin; URL is never cleared automatically — user can retry.
                store.authState.value.shouldBeInstanceOf<AuthState.NeedsLogin>()
            }
        }

        test("live policy CLOSED flips openRegistration to false while on the login screen") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.save(any(), any()) } returns Unit
                everySuspend { storage.delete(any()) } returns Unit
                // Cached open → clearAuthTokens lands on NeedsLogin(openRegistration = true).
                everySuspend { storage.read("open_registration") } returns "true"
                val policy = MutableStateFlow(RegistrationPolicy.OPEN)
                val store =
                    createStore(
                        storage = storage,
                        policyStream = FakePolicyStream(policy),
                        scope = backgroundScope,
                    )
                store.clearAuthTokens()

                store.authState.test {
                    // On the login screen with registration open.
                    awaitItem()
                        .shouldBeInstanceOf<AuthState.NeedsLogin>()
                        .openRegistration shouldBe true

                    // Admin closes registration → the stream pushes CLOSED → Sign Up flips off live.
                    policy.value = RegistrationPolicy.CLOSED

                    awaitItem()
                        .shouldBeInstanceOf<AuthState.NeedsLogin>()
                        .openRegistration shouldBe false

                    // The new value is cached for the offline-first fallback.
                    verifySuspend { storage.save("open_registration", "false") }
                }
            }
        }

        // ========== Regression tests ==========

        test("initializeAuthState requires login when token exists but userId missing") {
            runTest {
                // Token exists but userId is missing (inconsistent state — partial save or
                // storage corruption). Must require re-login rather than render placeholders.
                val storage = createMockStorage()
                val serverConfig = createMockServerConfig()
                everySuspend { serverConfig.getServerUrl() } returns ServerUrl("https://api.example.com")
                everySuspend { storage.read("access_token") } returns "access123"
                everySuspend { storage.read("user_id") } returns null
                everySuspend { storage.read("session_id") } returns "session789"
                everySuspend { storage.read("pending_user_id") } returns null
                everySuspend { storage.read("open_registration") } returns null
                everySuspend { storage.delete(any()) } returns Unit
                val store = createStore(storage = storage, serverConfig = serverConfig)

                store.initializeAuthState()

                store.authState.value.shouldBeInstanceOf<AuthState.NeedsLogin>()
            }
        }

        test("initializeAuthState requires login when token exists but sessionId missing") {
            runTest {
                val storage = createMockStorage()
                val serverConfig = createMockServerConfig()
                everySuspend { serverConfig.getServerUrl() } returns ServerUrl("https://api.example.com")
                everySuspend { storage.read("access_token") } returns "access123"
                everySuspend { storage.read("user_id") } returns "user001"
                everySuspend { storage.read("session_id") } returns null
                everySuspend { storage.read("pending_user_id") } returns null
                everySuspend { storage.read("open_registration") } returns null
                everySuspend { storage.delete(any()) } returns Unit
                val store = createStore(storage = storage, serverConfig = serverConfig)

                store.initializeAuthState()

                store.authState.value.shouldBeInstanceOf<AuthState.NeedsLogin>()
            }
        }

        test("initializeAuthState clears tokens when incomplete auth state detected") {
            runTest {
                val storage = createMockStorage()
                val serverConfig = createMockServerConfig()
                everySuspend { serverConfig.getServerUrl() } returns ServerUrl("https://api.example.com")
                everySuspend { storage.read("access_token") } returns "access123"
                everySuspend { storage.read("user_id") } returns null
                everySuspend { storage.read("session_id") } returns null
                everySuspend { storage.read("pending_user_id") } returns null
                everySuspend { storage.read("open_registration") } returns null
                everySuspend { storage.delete(any()) } returns Unit
                val store = createStore(storage = storage, serverConfig = serverConfig)

                store.initializeAuthState()

                verifySuspend { storage.delete("access_token") }
                verifySuspend { storage.delete("refresh_token") }
            }
        }

        // ── Session lapse (spec §6.2) ────────────────────────────────────────────────

        test("clearSessionCredentials drops tokens, KEEPS the user id, and lands in SessionLapsed") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.save(any(), any()) } returns Unit
                everySuspend { storage.delete(any()) } returns Unit
                everySuspend { storage.read(any()) } returns null
                everySuspend { storage.read("user_id") } returns "user-1"
                val store = createStore(storage = storage)
                store.saveAuthTokens(AccessToken("a"), RefreshToken("r"), "s1", "user-1")

                store.clearSessionCredentials()

                store.authState.value shouldBe AuthState.SessionLapsed(UserId("user-1"))
                verifySuspend { storage.delete("access_token") }
                verifySuspend { storage.delete("refresh_token") }
                verifySuspend { storage.delete("session_id") }
                verifySuspend(VerifyMode.exactly(0)) { storage.delete("user_id") }
            }
        }

        test("clearSessionCredentials with no persisted user id falls back to the full clear") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.save(any(), any()) } returns Unit
                everySuspend { storage.delete(any()) } returns Unit
                everySuspend { storage.read(any()) } returns null

                val store = createStore(storage = storage)
                store.clearSessionCredentials()

                store.authState.value.shouldBeInstanceOf<AuthState.NeedsLogin>()
                verifySuspend { storage.delete("user_id") }
            }
        }

        // ── Cold-start derivation matrix (spec T15, offline-first — no network call) ──

        test("deriveAuthState: persisted userId WITHOUT an access token derives SessionLapsed") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.read(any()) } returns null
                everySuspend { storage.read("user_id") } returns "user-1"
                val serverConfig = createMockServerConfig()
                everySuspend { serverConfig.getServerUrl() } returns ServerUrl("http://test:8080")
                val store = createStore(storage = storage, serverConfig = serverConfig)

                store.initializeAuthState()

                store.authState.value shouldBe AuthState.SessionLapsed(UserId("user-1"))
            }
        }

        test("deriveAuthState: fresh install (no userId, no tokens) still derives NeedsLogin — the locked cold-start exception") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.read(any()) } returns null
                val serverConfig = createMockServerConfig()
                everySuspend { serverConfig.getServerUrl() } returns ServerUrl("http://test:8080")
                val store = createStore(storage = storage, serverConfig = serverConfig)

                store.initializeAuthState()

                store.authState.value.shouldBeInstanceOf<AuthState.NeedsLogin>()
            }
        }

        test("deriveAuthState: token WITHOUT userId still triggers the corruption clear to NeedsLogin") {
            runTest {
                val storage = createMockStorage()
                everySuspend { storage.read(any()) } returns null
                everySuspend { storage.read("access_token") } returns "orphan-token"
                everySuspend { storage.delete(any()) } returns Unit
                val serverConfig = createMockServerConfig()
                everySuspend { serverConfig.getServerUrl() } returns ServerUrl("http://test:8080")
                val store = createStore(storage = storage, serverConfig = serverConfig)

                store.initializeAuthState()

                store.authState.value.shouldBeInstanceOf<AuthState.NeedsLogin>()
                verifySuspend { storage.delete("access_token") }
            }
        }
    })
