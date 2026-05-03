package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.client.checkIs
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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for [AuthSessionStore] — the auth slice extracted from
 * `SettingsRepositoryImpl`. Mocks `SecureStorage`, `ServerConfig`, and
 * `InstanceRepository`; everything auth-shaped is in scope here.
 */
class AuthSessionStoreTest {
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

    @Test
    fun `initial auth state is Initializing`() =
        runTest {
            val store = createStore()
            // Initializing prevents flash of wrong screen on app startup.
            checkIs<AuthState.Initializing>(store.authState.value)
        }

    @Test
    fun `saveAuthTokens stores all tokens and updates state to Authenticated`() =
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

            val state = assertIs<AuthState.Authenticated>(store.authState.value)
            assertEquals("user001", state.userId.value)
            assertEquals("session789", state.sessionId.value)
        }

    @Test
    fun `getAccessToken returns stored token`() =
        runTest {
            val storage = createMockStorage()
            everySuspend { storage.read("access_token") } returns "access123"
            val store = createStore(storage = storage)

            assertEquals(AccessToken("access123"), store.getAccessToken())
        }

    @Test
    fun `getRefreshToken returns stored token`() =
        runTest {
            val storage = createMockStorage()
            everySuspend { storage.read("refresh_token") } returns "refresh456"
            val store = createStore(storage = storage)

            assertEquals(RefreshToken("refresh456"), store.getRefreshToken())
        }

    @Test
    fun `getSessionId returns stored session ID`() =
        runTest {
            val storage = createMockStorage()
            everySuspend { storage.read("session_id") } returns "session789"
            val store = createStore(storage = storage)

            assertEquals("session789", store.getSessionId())
        }

    @Test
    fun `getUserId returns stored user ID`() =
        runTest {
            val storage = createMockStorage()
            everySuspend { storage.read("user_id") } returns "user001"
            val store = createStore(storage = storage)

            assertEquals("user001", store.getUserId())
        }

    @Test
    fun `updateAccessToken updates only access token`() =
        runTest {
            val storage = createMockStorage()
            everySuspend { storage.save("access_token", "newAccess") } returns Unit
            val store = createStore(storage = storage)

            store.updateAccessToken(AccessToken("newAccess"))

            verifySuspend { storage.save("access_token", "newAccess") }
        }

    @Test
    fun `clearAuthTokens removes all auth data and updates state to NeedsLogin`() =
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

            checkIs<AuthState.NeedsLogin>(store.authState.value)
        }

    @Test
    fun `isAuthenticated returns true when access token exists`() =
        runTest {
            val storage = createMockStorage()
            everySuspend { storage.read("access_token") } returns "access123"
            val store = createStore(storage = storage)

            assertTrue(store.isAuthenticated())
        }

    @Test
    fun `isAuthenticated returns false when access token missing`() =
        runTest {
            val storage = createMockStorage()
            everySuspend { storage.read("access_token") } returns null
            val store = createStore(storage = storage)

            assertFalse(store.isAuthenticated())
        }

    @Test
    fun `initializeAuthState sets NeedsServerUrl when no URL configured`() =
        runTest {
            val serverConfig = createMockServerConfig()
            everySuspend { serverConfig.getServerUrl() } returns null
            val store = createStore(serverConfig = serverConfig)

            store.initializeAuthState()

            checkIs<AuthState.NeedsServerUrl>(store.authState.value)
        }

    @Test
    fun `initializeAuthState sets Authenticated when tokens and IDs present`() =
        runTest {
            val storage = createMockStorage()
            val serverConfig = createMockServerConfig()
            everySuspend { serverConfig.getServerUrl() } returns ServerUrl("https://api.example.com")
            everySuspend { storage.read("access_token") } returns "access123"
            everySuspend { storage.read("user_id") } returns "user001"
            everySuspend { storage.read("session_id") } returns "session789"
            val store = createStore(storage = storage, serverConfig = serverConfig)

            store.initializeAuthState()

            val state = assertIs<AuthState.Authenticated>(store.authState.value)
            assertEquals("user001", state.userId.value)
            assertEquals("session789", state.sessionId.value)
        }

    @Test
    fun `initializeAuthState lands on NeedsLogin when URL present but no tokens or pending`() =
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

            checkIs<AuthState.NeedsLogin>(store.authState.value)
        }

    @Test
    fun `checkServerStatus sets NeedsSetup when server requires setup`() =
        runTest {
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            everySuspend { storage.save(any(), any()) } returns Unit
            everySuspend { instanceRepository.getInstance(forceRefresh = true) } returns
                Success(createTestInstance(setupRequired = true))
            val store = createStore(storage = storage, instanceRepository = instanceRepository)

            store.checkServerStatus()

            checkIs<AuthState.NeedsSetup>(store.authState.value)
        }

    @Test
    fun `checkServerStatus sets NeedsLogin on network failure without clearing URL`() =
        runTest {
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            everySuspend { storage.read("open_registration") } returns null
            everySuspend { instanceRepository.getInstance(forceRefresh = true) } returns
                Failure(Exception("Network error"))
            val store = createStore(storage = storage, instanceRepository = instanceRepository)

            store.checkServerStatus()

            // Stays in NeedsLogin; URL is never cleared automatically — user can retry.
            checkIs<AuthState.NeedsLogin>(store.authState.value)
        }

    // ========== Regression tests ==========

    @Test
    fun `initializeAuthState requires login when token exists but userId missing`() =
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

            checkIs<AuthState.NeedsLogin>(store.authState.value)
        }

    @Test
    fun `initializeAuthState requires login when token exists but sessionId missing`() =
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

            checkIs<AuthState.NeedsLogin>(store.authState.value)
        }

    @Test
    fun `initializeAuthState clears tokens when incomplete auth state detected`() =
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
