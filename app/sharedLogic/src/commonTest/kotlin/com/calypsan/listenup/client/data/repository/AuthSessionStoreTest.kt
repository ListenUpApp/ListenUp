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

/** In-memory [SecureStorage] that records the order of writes — for the C8 epoch + C9 order tests. */
private class RecordingStorage : SecureStorage {
    val data = mutableMapOf<String, String>()
    val saveOrder = mutableListOf<String>()

    override suspend fun save(
        key: String,
        value: String,
    ) {
        saveOrder += key
        data[key] = value
    }

    override suspend fun read(key: String): String? = data[key]

    override suspend fun delete(key: String) {
        data.remove(key)
    }

    override suspend fun clear() {
        data.clear()
    }
}

private fun createMockServerConfig(): ServerConfig = mock<ServerConfig>()

private fun createMockInstanceRepository(): InstanceRepository = mock<InstanceRepository>()

/** A policy stream backed by a supplied flow; defaults to a silent stream (no live updates). */
private class FakePolicyStream(
    private val flow: Flow<RegistrationPolicy> = emptyFlow(),
) : RegistrationPolicyStream {
    override fun streamPolicy(): Flow<RegistrationPolicy> = flow
}

/** An [InstanceRepository] that answers the one question a signed-out boot asks it. */
private fun serverSaying(setupRequired: Boolean): InstanceRepository =
    createMockInstanceRepository().also {
        everySuspend { it.getServerInfo(forceRefresh = true) } returns
            AppResult.Success(createTestServerInfo(setupRequired = setupRequired))
    }

/** A [ServerConfig] that has a URL — i.e. past the `NeedsServerUrl` branch. */
private fun configuredServer(): ServerConfig =
    createMockServerConfig().also {
        everySuspend { it.getServerUrl() } returns ServerUrl("http://test:8088")
    }

/** Storage holding no credentials at all: the cold-start-with-no-session shape. */
private fun signedOutStorage(): SecureStorage = RecordingStorage()

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

        test("saveAuthTokens writes refresh/session/user BEFORE access so no reader pairs new access with stale refresh (C9)") {
            runTest {
                val storage = RecordingStorage()
                val store = createStore(storage = storage)

                store.saveAuthTokens(AccessToken("a"), RefreshToken("r"), "s", "u")

                val accessIdx = storage.saveOrder.indexOf("access_token")
                accessIdx shouldBe storage.saveOrder.size - 1 // access lands last
                (storage.saveOrder.indexOf("refresh_token") < accessIdx) shouldBe true
                (storage.saveOrder.indexOf("session_id") < accessIdx) shouldBe true
                (storage.saveOrder.indexOf("user_id") < accessIdx) shouldBe true
            }
        }

        test("a stale-epoch saveAuthTokens no-ops after a logout bumped the epoch — no resurrection (C8)") {
            runTest {
                val storage = RecordingStorage()
                val store = createStore(storage = storage)
                store.saveAuthTokens(AccessToken("a0"), RefreshToken("r0"), "s0", "u0")
                val epoch = store.currentAuthEpoch()

                // Logout wipes credentials and advances the epoch.
                store.clearAuthTokens()
                storage.data["access_token"] shouldBe null

                // A late refresh captured the OLD epoch and tries to persist — must be ignored.
                store.saveAuthTokens(AccessToken("a1"), RefreshToken("r1"), "s1", "u1", ifEpoch = epoch)

                storage.data["access_token"] shouldBe null
                storage.data["refresh_token"] shouldBe null
                store.authState.value.shouldBeInstanceOf<AuthState.NeedsLogin>()
            }
        }

        test("a current-epoch saveAuthTokens applies (the normal single-flight refresh path, C1/C8)") {
            runTest {
                val storage = RecordingStorage()
                val store = createStore(storage = storage)
                store.saveAuthTokens(AccessToken("a0"), RefreshToken("r0"), "s0", "u0")
                val epoch = store.currentAuthEpoch()

                store.saveAuthTokens(AccessToken("a1"), RefreshToken("r1"), "s1", "u1", ifEpoch = epoch)

                storage.data["access_token"] shouldBe "a1"
                storage.data["refresh_token"] shouldBe "r1"
            }
        }

        test("a completed setup does not strand the next cold start on the setup form") {
            runTest {
                val storage = RecordingStorage()
                val store = createStore(storage = storage)

                // Completing setup funnels through saveAuthTokens exactly as login and register do.
                store.saveAuthTokens(AccessToken("a"), RefreshToken("r"), "s", "u")

                // Sign out, then relaunch: a new store over the same storage IS a cold start.
                store.clearAuthTokens()
                val serverConfig = createMockServerConfig()
                everySuspend { serverConfig.getServerUrl() } returns ServerUrl("https://api.example.com")
                val relaunched =
                    createStore(
                        storage = storage,
                        serverConfig = serverConfig,
                        instanceRepository = serverSaying(setupRequired = false),
                    )
                relaunched.initializeAuthState()

                // The admin this setup created is exactly what the server now reports, so the
                // reader lands on sign-in. Nothing about that answer is remembered locally —
                // it is re-asked on every signed-out boot.
                relaunched.authState.value.shouldBeInstanceOf<AuthState.NeedsLogin>()
                storage.data.containsKey("setup_required") shouldBe false
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
                everySuspend { storage.save(any(), any()) } returns Unit
                val store =
                    createStore(
                        storage = storage,
                        serverConfig = serverConfig,
                        instanceRepository = serverSaying(setupRequired = false),
                    )

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

        test("initializeAuthState routes a signed-out boot to setup when the server says it has no admin") {
            runTest {
                // A fresh server (no admin yet) must reach the setup screen, or its "Create Account"
                // leads only to the approval-gated request flow with nobody to approve it — a
                // fresh-server dead-end. The answer comes from the server on this very boot; there
                // is no cached flag to honour.
                val store =
                    createStore(
                        storage = signedOutStorage(),
                        serverConfig = configuredServer(),
                        instanceRepository = serverSaying(setupRequired = true),
                    )

                store.initializeAuthState()

                store.authState.value.shouldBeInstanceOf<AuthState.NeedsSetup>()
            }
        }

        test("initializeAuthState routes to sign-in when the server has an admin, whatever a stale cache says") {
            runTest {
                // The reported bug. A first boot against an empty server used to persist
                // setupRequired=true; if the admin was then created anywhere else (another device,
                // a second browser, the CLI), every later boot re-read that flag and offered to
                // create an admin the server already had — discoverable only by filling in the
                // whole form and being told it was pointless.
                val storage = RecordingStorage()
                storage.data["setup_required"] = "true"
                val store =
                    createStore(
                        storage = storage,
                        serverConfig = configuredServer(),
                        instanceRepository = serverSaying(setupRequired = false),
                    )

                store.initializeAuthState()

                store.authState.value.shouldBeInstanceOf<AuthState.NeedsLogin>()
            }
        }

        test("initializeAuthState routes to setup on a wiped server, even though the last boot cached otherwise") {
            runTest {
                // The mirror-image dead-end, and the reason the cache could not simply be corrected
                // rather than deleted: a server whose users were wiped needs setup again, and a
                // stale "false" would pin the reader to a sign-in screen no account can satisfy.
                val storage = RecordingStorage()
                storage.data["setup_required"] = "false"
                val store =
                    createStore(
                        storage = storage,
                        serverConfig = configuredServer(),
                        instanceRepository = serverSaying(setupRequired = true),
                    )

                store.initializeAuthState()

                store.authState.value.shouldBeInstanceOf<AuthState.NeedsSetup>()
            }
        }

        test("initializeAuthState falls back to sign-in when the server cannot be reached") {
            runTest {
                // Never Stranded, pointed the safe way: with no answer available, a sign-in screen
                // is merely useless where a setup form would be actively wrong. "Create account"
                // stays honest via the separately cached open-registration flag.
                val storage = RecordingStorage()
                storage.data["open_registration"] = "true"
                val instanceRepository = createMockInstanceRepository()
                everySuspend { instanceRepository.getServerInfo(forceRefresh = true) } returns
                    Failure(Exception("Network error"))
                val store =
                    createStore(
                        storage = storage,
                        serverConfig = configuredServer(),
                        instanceRepository = instanceRepository,
                    )

                store.initializeAuthState()

                val state = store.authState.value.shouldBeInstanceOf<AuthState.NeedsLogin>()
                state.openRegistration shouldBe true
            }
        }

        test("initializeAuthState reaches no server at all when a session is already held") {
            runTest {
                // The cost guard on asking the server every signed-out boot: the overwhelmingly
                // common boot is a signed-in one, and it must still resolve from storage alone.
                val storage = RecordingStorage()
                val store = createStore(storage = storage, serverConfig = configuredServer())
                store.saveAuthTokens(AccessToken("a"), RefreshToken("r"), "s", "u")

                val instanceRepository = createMockInstanceRepository()
                val relaunched =
                    createStore(
                        storage = storage,
                        serverConfig = configuredServer(),
                        instanceRepository = instanceRepository,
                    )
                relaunched.initializeAuthState()

                relaunched.authState.value.shouldBeInstanceOf<AuthState.Authenticated>()
                verifySuspend(VerifyMode.exactly(0)) { instanceRepository.getServerInfo(any()) }
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

        // ── Cold-start derivation matrix (spec T15) ──
        //
        // Offline for every branch a signed-in device can land in. The fresh-install row is the one
        // exception: with no session at all, "setup or sign-in?" is asked of the server.

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
                everySuspend { storage.save(any(), any()) } returns Unit
                val serverConfig = createMockServerConfig()
                everySuspend { serverConfig.getServerUrl() } returns ServerUrl("http://test:8080")
                // A fresh install holds no session, so this row is the branch that asks the server.
                // An instance that already has an admin answers "sign in" — the locked expectation
                // below is unchanged, but it is now the server's answer rather than a cache's.
                val store =
                    createStore(
                        storage = storage,
                        serverConfig = serverConfig,
                        instanceRepository = serverSaying(setupRequired = false),
                    )

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
