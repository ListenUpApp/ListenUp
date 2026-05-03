package com.calypsan.listenup.client.data.repository

import app.cash.turbine.test
import com.calypsan.listenup.client.core.SecureStorage
import com.calypsan.listenup.client.core.ServerUrl
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.PreferenceChangeEvent
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.calypsan.listenup.client.domain.model.AuthState as DomainAuthState

/**
 * Tests for the *non-auth* surface of [SettingsRepositoryImpl] — server URL
 * plumbing, library identity, library + playback preferences. The auth
 * slice has its own test in [AuthSessionStoreTest]; here we mock the
 * `AuthSession` collaborator at the seam.
 */
class SettingsRepositoryTest {
    private fun createMockStorage(): SecureStorage = mock<SecureStorage>()

    private fun createMockAuthSession(): AuthSession = mock<AuthSession>()

    private fun createRepository(
        storage: SecureStorage = createMockStorage(),
        authSession: AuthSession = createMockAuthSession(),
    ): SettingsRepositoryImpl = SettingsRepositoryImpl(storage, authSession)

    @Test
    fun `setServerUrl persists the URL and triggers offline derive when already authenticated`() =
        runTest {
            val storage = createMockStorage()
            val authSession = createMockAuthSession()
            everySuspend { storage.save("server_url", "https://api.example.com") } returns Unit
            everySuspend { authSession.isAuthenticated() } returns true
            everySuspend { authSession.initializeAuthState() } returns Unit
            val repository = createRepository(storage = storage, authSession = authSession)

            repository.setServerUrl(ServerUrl("https://api.example.com"))

            verifySuspend { storage.save("server_url", "https://api.example.com") }
            verifySuspend { authSession.initializeAuthState() }
        }

    @Test
    fun `setServerUrl persists the URL and asks server status when not yet authenticated`() =
        runTest {
            val storage = createMockStorage()
            val authSession = createMockAuthSession()
            val authStateFlow: StateFlow<DomainAuthState> = MutableStateFlow(DomainAuthState.Initializing)
            everySuspend { storage.save("server_url", "https://api.example.com") } returns Unit
            everySuspend { authSession.isAuthenticated() } returns false
            everySuspend { authSession.checkServerStatus() } returns DomainAuthState.NeedsLogin()
            // checkServerStatus is suspend & returns AuthState; just stub it.
            val repository = createRepository(storage = storage, authSession = authSession)

            repository.setServerUrl(ServerUrl("https://api.example.com"))

            verifySuspend { storage.save("server_url", "https://api.example.com") }
            verifySuspend { authSession.checkServerStatus() }
            // authStateFlow is unused but kept to document the seam shape.
            assertNull(authStateFlow.value as? DomainAuthState.NeedsLogin)
        }

    @Test
    fun `getServerUrl returns stored URL`() =
        runTest {
            val storage = createMockStorage()
            everySuspend { storage.read("server_url") } returns "https://api.example.com"
            val repository = createRepository(storage = storage)

            assertEquals(ServerUrl("https://api.example.com"), repository.getServerUrl())
        }

    @Test
    fun `getServerUrl returns null when not configured`() =
        runTest {
            val storage = createMockStorage()
            everySuspend { storage.read("server_url") } returns null
            val repository = createRepository(storage = storage)

            assertNull(repository.getServerUrl())
        }

    @Test
    fun `clearAll wipes secure storage and re-derives auth state`() =
        runTest {
            val storage = createMockStorage()
            val authSession = createMockAuthSession()
            everySuspend { storage.clear() } returns Unit
            everySuspend { authSession.initializeAuthState() } returns Unit
            val repository = createRepository(storage = storage, authSession = authSession)

            repository.clearAll()

            verifySuspend { storage.clear() }
            verifySuspend { authSession.initializeAuthState() }
        }

    @Test
    fun `disconnectFromServer drops auth and URL plumbing then re-derives state`() =
        runTest {
            val storage = createMockStorage()
            val authSession = createMockAuthSession()
            everySuspend { authSession.clearAuthTokens() } returns Unit
            everySuspend { authSession.clearPendingRegistration() } returns Unit
            everySuspend { authSession.initializeAuthState() } returns Unit
            everySuspend { storage.delete(any()) } returns Unit
            val repository = createRepository(storage = storage, authSession = authSession)

            repository.disconnectFromServer()

            verifySuspend { authSession.clearAuthTokens() }
            verifySuspend { authSession.clearPendingRegistration() }
            verifySuspend { storage.delete("server_url") }
            verifySuspend { storage.delete("server_remote_url") }
            verifySuspend { storage.delete("active_url") }
            verifySuspend { storage.delete("connected_library_id") }
            verifySuspend { authSession.initializeAuthState() }
        }

    @Test
    fun `hasServerConfigured returns true when URL configured`() =
        runTest {
            val storage = createMockStorage()
            everySuspend { storage.read("server_url") } returns "https://api.example.com"
            val repository = createRepository(storage = storage)

            assertTrue(repository.hasServerConfigured())
        }

    @Test
    fun `hasServerConfigured returns false when URL not configured`() =
        runTest {
            val storage = createMockStorage()
            everySuspend { storage.read("server_url") } returns null
            val repository = createRepository(storage = storage)

            assertFalse(repository.hasServerConfigured())
        }

    // ========== Spatial playback ==========

    @Test
    fun `getSpatialPlayback returns true by default`() =
        runTest {
            val storage = createMockStorage()
            everySuspend { storage.read("spatial_playback") } returns null
            val repository = createRepository(storage = storage)

            assertTrue(repository.getSpatialPlayback())
        }

    @Test
    fun `getSpatialPlayback returns false when set to false`() =
        runTest {
            val storage = createMockStorage()
            everySuspend { storage.read("spatial_playback") } returns "false"
            val repository = createRepository(storage = storage)

            assertFalse(repository.getSpatialPlayback())
        }

    @Test
    fun `getSpatialPlayback returns true when set to true`() =
        runTest {
            val storage = createMockStorage()
            everySuspend { storage.read("spatial_playback") } returns "true"
            val repository = createRepository(storage = storage)

            assertTrue(repository.getSpatialPlayback())
        }

    @Test
    fun `setSpatialPlayback stores false correctly`() =
        runTest {
            val storage = createMockStorage()
            everySuspend { storage.save("spatial_playback", "false") } returns Unit
            val repository = createRepository(storage = storage)

            repository.setSpatialPlayback(false)

            verifySuspend { storage.save("spatial_playback", "false") }
        }

    @Test
    fun `setSpatialPlayback stores true correctly`() =
        runTest {
            val storage = createMockStorage()
            everySuspend { storage.save("spatial_playback", "true") } returns Unit
            val repository = createRepository(storage = storage)

            repository.setSpatialPlayback(true)

            verifySuspend { storage.save("spatial_playback", "true") }
        }

    @Test
    fun `setSpatialPlayback and getSpatialPlayback persist value correctly`() =
        runTest {
            val storage = createMockStorage()
            everySuspend { storage.save("spatial_playback", "false") } returns Unit
            everySuspend { storage.read("spatial_playback") } returns "false"
            val repository = createRepository(storage = storage)

            repository.setSpatialPlayback(false)
            val result = repository.getSpatialPlayback()

            assertFalse(result)
            verifySuspend { storage.save("spatial_playback", "false") }
        }

    // ========== Default playback speed ==========

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `setDefaultPlaybackSpeed saves speed and emits preference change event`() =
        runTest(UnconfinedTestDispatcher()) {
            val storage = createMockStorage()
            everySuspend { storage.save("default_playback_speed", "1.5") } returns Unit
            val repository = createRepository(storage = storage)

            // Start collecting before emitting (async starts immediately under UnconfinedTestDispatcher)
            val eventDeferred = async { repository.preferenceChanges.first() }

            repository.setDefaultPlaybackSpeed(1.5f)

            val receivedEvent = eventDeferred.await()
            verifySuspend { storage.save("default_playback_speed", "1.5") }
            val speedChangedEvent = assertIs<PreferenceChangeEvent.PlaybackSpeedChanged>(receivedEvent)
            assertEquals(1.5f, speedChangedEvent.speed)
        }

    @Test
    fun `getDefaultPlaybackSpeed returns default when not set`() =
        runTest {
            val storage = createMockStorage()
            everySuspend { storage.read("default_playback_speed") } returns null
            val repository = createRepository(storage = storage)

            assertEquals(1.0f, repository.getDefaultPlaybackSpeed())
        }

    @Test
    fun `getDefaultPlaybackSpeed returns stored speed`() =
        runTest {
            val storage = createMockStorage()
            everySuspend { storage.read("default_playback_speed") } returns "1.25"
            val repository = createRepository(storage = storage)

            assertEquals(1.25f, repository.getDefaultPlaybackSpeed())
        }

    @Test
    fun `observeDefaultPlaybackSpeed emits current value on first collect`() =
        runTest {
            val storage = createMockStorage()
            everySuspend { storage.read("default_playback_speed") } returns "1.5"
            val repository = createRepository(storage = storage)

            repository.observeDefaultPlaybackSpeed().test {
                assertEquals(1.5f, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `observeDefaultPlaybackSpeed re-emits when setDefaultPlaybackSpeed is called`() =
        runTest {
            val storage = createMockStorage()
            everySuspend { storage.read("default_playback_speed") } returns "1.0"
            everySuspend { storage.save("default_playback_speed", "1.75") } returns Unit
            val repository = createRepository(storage = storage)

            repository.observeDefaultPlaybackSpeed().test {
                assertEquals(1.0f, awaitItem())
                repository.setDefaultPlaybackSpeed(1.75f)
                assertEquals(1.75f, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
}
