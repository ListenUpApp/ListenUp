package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.checkIs
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.client.core.SecureStorage
import com.calypsan.listenup.client.core.ServerUrl
import com.calypsan.listenup.client.domain.model.Instance
import com.calypsan.listenup.client.domain.model.InstanceId
import com.calypsan.listenup.client.domain.repository.AuthState
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.PreferenceChangeEvent
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import com.calypsan.listenup.client.core.Success
import com.calypsan.listenup.client.core.Failure

/**
 * Tests for SettingsRepository.
 * Uses Mokkery for mocking SecureStorage and InstanceRepository.
 */
class SettingsRepositoryTest {
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

    private fun createMockInstanceRepository(): InstanceRepository = mock<InstanceRepository>()

    @Test
    fun `initial auth state is Initializing`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )

            // Then - initial state before initializeAuthState is called
            // Initializing prevents flash of wrong screen on app startup
            checkIs<AuthState.Initializing>(repository.authState.value)
        }

    @Test
    fun `setServerUrl stores URL in secure storage`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            val url = ServerUrl("https://api.example.com")
            everySuspend { storage.save("server_url", "https://api.example.com") } returns Unit
            // Mock deriveAuthState calls
            everySuspend { storage.read("server_url") } returns "https://api.example.com"
            everySuspend { storage.read("access_token") } returns "token"
            everySuspend { storage.read("user_id") } returns "user001"
            everySuspend { storage.read("session_id") } returns "session789"

            // When
            repository.setServerUrl(url)

            // Then
            verifySuspend { storage.save("server_url", "https://api.example.com") }
        }

    @Test
    fun `getServerUrl returns stored URL`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.read("server_url") } returns "https://api.example.com"

            // When
            val result = repository.getServerUrl()

            // Then
            assertEquals(ServerUrl("https://api.example.com"), result)
        }

    @Test
    fun `getServerUrl returns null when not configured`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.read("server_url") } returns null

            // When
            val result = repository.getServerUrl()

            // Then
            assertNull(result)
        }

    @Test
    fun `saveAuthTokens stores all tokens and updates state to Authenticated`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.save(any(), any()) } returns Unit

            // When
            repository.saveAuthTokens(
                AccessToken("access123"),
                RefreshToken("refresh456"),
                "session789",
                "user001",
            )

            // Then
            verifySuspend { storage.save("access_token", "access123") }
            verifySuspend { storage.save("refresh_token", "refresh456") }
            verifySuspend { storage.save("session_id", "session789") }
            verifySuspend { storage.save("user_id", "user001") }

            val state = assertIs<AuthState.Authenticated>(repository.authState.value)
            assertEquals("user001", state.userId)
            assertEquals("session789", state.sessionId)
        }

    @Test
    fun `getAccessToken returns stored token`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.read("access_token") } returns "access123"

            // When
            val result = repository.getAccessToken()

            // Then
            assertEquals(AccessToken("access123"), result)
        }

    @Test
    fun `getRefreshToken returns stored token`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.read("refresh_token") } returns "refresh456"

            // When
            val result = repository.getRefreshToken()

            // Then
            assertEquals(RefreshToken("refresh456"), result)
        }

    @Test
    fun `getSessionId returns stored session ID`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.read("session_id") } returns "session789"

            // When
            val result = repository.getSessionId()

            // Then
            assertEquals("session789", result)
        }

    @Test
    fun `getUserId returns stored user ID`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.read("user_id") } returns "user001"

            // When
            val result = repository.getUserId()

            // Then
            assertEquals("user001", result)
        }

    @Test
    fun `updateAccessToken updates only access token`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.save("access_token", "newAccess") } returns Unit

            // When
            repository.updateAccessToken(AccessToken("newAccess"))

            // Then
            verifySuspend { storage.save("access_token", "newAccess") }
        }

    @Test
    fun `clearAuthTokens removes all auth data and updates state to NeedsLogin`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.delete(any()) } returns Unit
            everySuspend { storage.read("open_registration") } returns null

            // When
            repository.clearAuthTokens()

            // Then
            verifySuspend { storage.delete("access_token") }
            verifySuspend { storage.delete("refresh_token") }
            verifySuspend { storage.delete("session_id") }
            verifySuspend { storage.delete("user_id") }

            checkIs<AuthState.NeedsLogin>(repository.authState.value)
        }

    @Test
    fun `clearAll removes all data and updates state to NeedsServerUrl`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.clear() } returns Unit

            // When
            repository.clearAll()

            // Then
            verifySuspend { storage.clear() }
            checkIs<AuthState.NeedsServerUrl>(repository.authState.value)
        }

    @Test
    fun `isAuthenticated returns true when access token exists`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.read("access_token") } returns "access123"

            // When
            val result = repository.isAuthenticated()

            // Then
            assertTrue(result)
        }

    @Test
    fun `isAuthenticated returns false when access token missing`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.read("access_token") } returns null

            // When
            val result = repository.isAuthenticated()

            // Then
            assertFalse(result)
        }

    @Test
    fun `hasServerConfigured returns true when URL configured`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.read("server_url") } returns "https://api.example.com"

            // When
            val result = repository.hasServerConfigured()

            // Then
            assertTrue(result)
        }

    @Test
    fun `hasServerConfigured returns false when URL not configured`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.read("server_url") } returns null

            // When
            val result = repository.hasServerConfigured()

            // Then
            assertFalse(result)
        }

    @Test
    fun `initializeAuthState sets NeedsServerUrl when no URL configured`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.read("server_url") } returns null

            // When
            repository.initializeAuthState()

            // Then
            checkIs<AuthState.NeedsServerUrl>(repository.authState.value)
        }

    @Test
    fun `initializeAuthState sets Authenticated when tokens and IDs present`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.read("server_url") } returns "https://api.example.com"
            everySuspend { storage.read("access_token") } returns "access123"
            everySuspend { storage.read("user_id") } returns "user001"
            everySuspend { storage.read("session_id") } returns "session789"

            // When
            repository.initializeAuthState()

            // Then
            val state = assertIs<AuthState.Authenticated>(repository.authState.value)
            assertEquals("user001", state.userId)
            assertEquals("session789", state.sessionId)
        }

    @Test
    fun `initializeAuthState checks server when URL present but no tokens`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.read("server_url") } returns "https://api.example.com"
            everySuspend { storage.read("access_token") } returns null
            everySuspend { storage.read("user_id") } returns null
            everySuspend { storage.read("session_id") } returns null
            everySuspend { storage.read("pending_user_id") } returns null
            everySuspend { storage.read("open_registration") } returns null
            // Server returns setup not required
            everySuspend { instanceRepository.getInstance(forceRefresh = true) } returns
                Success(
                    createTestInstance(setupRequired = false),
                )

            // When
            repository.initializeAuthState()

            // Then
            checkIs<AuthState.NeedsLogin>(repository.authState.value)
        }

    @Test
    fun `checkServerStatus sets NeedsSetup when server requires setup`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.save(any(), any()) } returns Unit
            // Server returns setup required
            everySuspend { instanceRepository.getInstance(forceRefresh = true) } returns
                Success(
                    createTestInstance(setupRequired = true),
                )

            // When - checkServerStatus makes network call, initializeAuthState doesn't
            repository.checkServerStatus()

            // Then
            checkIs<AuthState.NeedsSetup>(repository.authState.value)
        }

    @Test
    fun `checkServerStatus sets NeedsLogin on network failure without clearing URL`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.read("open_registration") } returns null
            // Server unreachable
            everySuspend { instanceRepository.getInstance(forceRefresh = true) } returns
                Failure(Exception("Network error"))

            // When - checkServerStatus handles network errors gracefully
            repository.checkServerStatus()

            // Then - stays in NeedsLogin, doesn't clear URL (user can retry)
            checkIs<AuthState.NeedsLogin>(repository.authState.value)
        }

    @Test
    fun `getSpatialPlayback returns true by default`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.read("spatial_playback") } returns null

            // When
            val result = repository.getSpatialPlayback()

            // Then
            assertTrue(result)
        }

    @Test
    fun `getSpatialPlayback returns false when set to false`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.read("spatial_playback") } returns "false"

            // When
            val result = repository.getSpatialPlayback()

            // Then
            assertFalse(result)
        }

    @Test
    fun `getSpatialPlayback returns true when set to true`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.read("spatial_playback") } returns "true"

            // When
            val result = repository.getSpatialPlayback()

            // Then
            assertTrue(result)
        }

    @Test
    fun `setSpatialPlayback stores false correctly`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.save("spatial_playback", "false") } returns Unit

            // When
            repository.setSpatialPlayback(false)

            // Then
            verifySuspend { storage.save("spatial_playback", "false") }
        }

    @Test
    fun `setSpatialPlayback stores true correctly`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.save("spatial_playback", "true") } returns Unit

            // When
            repository.setSpatialPlayback(true)

            // Then
            verifySuspend { storage.save("spatial_playback", "true") }
        }

    @Test
    fun `setSpatialPlayback and getSpatialPlayback persist value correctly`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.save("spatial_playback", "false") } returns Unit
            everySuspend { storage.read("spatial_playback") } returns "false"

            // When
            repository.setSpatialPlayback(false)
            val result = repository.getSpatialPlayback()

            // Then
            assertFalse(result)
            verifySuspend { storage.save("spatial_playback", "false") }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `setDefaultPlaybackSpeed saves speed and emits preference change event`() =
        runTest(UnconfinedTestDispatcher()) {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.save("default_playback_speed", "1.5") } returns Unit

            // Start collecting before emitting (async starts immediately with UnconfinedTestDispatcher)
            val eventDeferred =
                async {
                    repository.preferenceChanges.first()
                }

            // When
            repository.setDefaultPlaybackSpeed(1.5f)

            // Then
            val receivedEvent = eventDeferred.await()
            verifySuspend { storage.save("default_playback_speed", "1.5") }
            val speedChangedEvent = assertIs<PreferenceChangeEvent.PlaybackSpeedChanged>(receivedEvent)
            assertEquals(1.5f, speedChangedEvent.speed)
        }

    @Test
    fun `getDefaultPlaybackSpeed returns default when not set`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.read("default_playback_speed") } returns null

            // When
            val result = repository.getDefaultPlaybackSpeed()

            // Then
            assertEquals(1.0f, result)
        }

    @Test
    fun `getDefaultPlaybackSpeed returns stored speed`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.read("default_playback_speed") } returns "1.25"

            // When
            val result = repository.getDefaultPlaybackSpeed()

            // Then
            assertEquals(1.25f, result)
        }

    // ========== observeDefaultPlaybackSpeed Tests ==========

    @Test
    fun `observeDefaultPlaybackSpeed emits current value on first collect`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            everySuspend { storage.read("default_playback_speed") } returns "1.5"
            val repository = SettingsRepositoryImpl(storage, instanceRepository)

            // When / Then
            repository.observeDefaultPlaybackSpeed().test {
                assertEquals(1.5f, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `observeDefaultPlaybackSpeed re-emits when setDefaultPlaybackSpeed is called`() =
        runTest {
            // Given
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            everySuspend { storage.read("default_playback_speed") } returns "1.0"
            everySuspend { storage.save("default_playback_speed", "1.75") } returns Unit
            val repository = SettingsRepositoryImpl(storage, instanceRepository)

            // When / Then
            repository.observeDefaultPlaybackSpeed().test {
                assertEquals(1.0f, awaitItem())
                repository.setDefaultPlaybackSpeed(1.75f)
                assertEquals(1.75f, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    // ========== Regression Tests ==========

    @Test
    fun `initializeAuthState requires login when token exists but userId missing`() =
        runTest {
            // Given: Token exists but userId is missing (inconsistent state)
            // This can happen if the app crashes during token save or storage is corrupted.
            // The app should NOT use a placeholder "pending" userId - it should require re-login.
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.read("server_url") } returns "https://api.example.com"
            everySuspend { storage.read("access_token") } returns "access123"
            everySuspend { storage.read("user_id") } returns null // Missing!
            everySuspend { storage.read("session_id") } returns "session789"
            everySuspend { storage.read("pending_user_id") } returns null
            everySuspend { storage.read("open_registration") } returns null
            // Mock clearAuthTokens calls
            everySuspend { storage.delete(any()) } returns Unit

            // When
            repository.initializeAuthState()

            // Then: Should require login, not use placeholder
            checkIs<AuthState.NeedsLogin>(repository.authState.value)
        }

    @Test
    fun `initializeAuthState requires login when token exists but sessionId missing`() =
        runTest {
            // Given: Token exists but sessionId is missing
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.read("server_url") } returns "https://api.example.com"
            everySuspend { storage.read("access_token") } returns "access123"
            everySuspend { storage.read("user_id") } returns "user001"
            everySuspend { storage.read("session_id") } returns null // Missing!
            everySuspend { storage.read("pending_user_id") } returns null
            everySuspend { storage.read("open_registration") } returns null
            everySuspend { storage.delete(any()) } returns Unit

            // When
            repository.initializeAuthState()

            // Then: Should require login, not use placeholder
            checkIs<AuthState.NeedsLogin>(repository.authState.value)
        }

    @Test
    fun `initializeAuthState clears tokens when incomplete auth state detected`() =
        runTest {
            // Given: Token exists but userId/sessionId missing
            val storage = createMockStorage()
            val instanceRepository = createMockInstanceRepository()
            val repository =
                SettingsRepositoryImpl(
                    storage,
                    instanceRepository,
                )
            everySuspend { storage.read("server_url") } returns "https://api.example.com"
            everySuspend { storage.read("access_token") } returns "access123"
            everySuspend { storage.read("user_id") } returns null
            everySuspend { storage.read("session_id") } returns null
            everySuspend { storage.read("pending_user_id") } returns null
            everySuspend { storage.read("open_registration") } returns null
            everySuspend { storage.delete(any()) } returns Unit

            // When
            repository.initializeAuthState()

            // Then: Tokens should be cleared
            verifySuspend { storage.delete("access_token") }
            verifySuspend { storage.delete("refresh_token") }
        }
}
