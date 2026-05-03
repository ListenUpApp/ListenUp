package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.core.SecureStorage
import com.calypsan.listenup.client.core.ServerUrl
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.model.Instance
import com.calypsan.listenup.client.domain.model.InstanceId
import com.calypsan.listenup.client.domain.repository.InstanceRepository
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
import kotlin.time.Instant

private fun createTestInstance(setupRequired: Boolean): Instance =
    Instance(
        id = InstanceId("test-instance"),
        name = "Test Instance",
        version = "1.0.0",
        localUrl = "http://localhost:8080",
        remoteUrl = null,
        setupRequired = setupRequired,
        createdAt = Instant.DISTANT_PAST,
        updatedAt = Instant.DISTANT_PAST,
    )

private fun createMockStorage(): SecureStorage = mock<SecureStorage>()

private fun createMockServerConfig(): ServerConfig = mock<ServerConfig>()

private fun createMockInstanceRepository(): InstanceRepository = mock<InstanceRepository>()

private fun createStore(
    storage: SecureStorage = createMockStorage(),
    serverConfig: ServerConfig = createMockServerConfig(),
    instanceRepository: InstanceRepository = createMockInstanceRepository(),
): AuthSessionStore = AuthSessionStore(storage, serverConfig, instanceRepository)

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
                everySuspend { instanceRepository.getInstance(forceRefresh = true) } returns
                    Success(createTestInstance(setupRequired = true))
                val store = createStore(storage = storage, instanceRepository = instanceRepository)

                store.checkServerStatus()

                store.authState.value.shouldBeInstanceOf<AuthState.NeedsSetup>()
            }
        }

        test("checkServerStatus sets NeedsLogin on network failure without clearing URL") {
            runTest {
                val storage = createMockStorage()
                val instanceRepository = createMockInstanceRepository()
                everySuspend { storage.read("open_registration") } returns null
                everySuspend { instanceRepository.getInstance(forceRefresh = true) } returns
                    Failure(Exception("Network error"))
                val store = createStore(storage = storage, instanceRepository = instanceRepository)

                store.checkServerStatus()

                // Stays in NeedsLogin; URL is never cleared automatically — user can retry.
                store.authState.value.shouldBeInstanceOf<AuthState.NeedsLogin>()
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
    })
