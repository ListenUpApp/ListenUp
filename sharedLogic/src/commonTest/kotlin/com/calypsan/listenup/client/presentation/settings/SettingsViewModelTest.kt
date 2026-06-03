package com.calypsan.listenup.client.presentation.settings

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.ThemeMode
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.LibraryPreferences
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.data.remote.RpcCacheInvalidator
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserPreferences
import com.calypsan.listenup.client.domain.repository.UserPreferencesRepository
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.calypsan.listenup.client.core.Failure

/**
 * Tests for SettingsViewModel.
 *
 * Tests cover:
 * - Loading settings on init
 * - Updating individual settings
 * - Optimistic UI updates
 * - Server sync for synced settings
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    // ========== Test Fixture Builder ==========

    private class TestFixture {
        val libraryPreferences: LibraryPreferences = mock()
        val playbackPreferences: PlaybackPreferences = mock()
        val localPreferences: LocalPreferences = mock()
        val userPreferencesRepository: UserPreferencesRepository = mock()
        val instanceRepository: InstanceRepository = mock()
        val serverConfig: ServerConfig = mock()
        val authSession: AuthSession = mock()
        val syncRepository: SyncRepository = mock()
        val rpcCacheInvalidator: RpcCacheInvalidator = mock()

        // StateFlows for local preferences (mocked as MutableStateFlow)
        val themeModeFlow = MutableStateFlow(ThemeMode.SYSTEM)
        val dynamicColorsFlow = MutableStateFlow(true)
        val autoRewindFlow = MutableStateFlow(true)
        val wifiOnlyFlow = MutableStateFlow(true)
        val autoRemoveFlow = MutableStateFlow(false)
        val hapticFeedbackFlow = MutableStateFlow(true)

        fun build(): SettingsViewModel =
            SettingsViewModel(
                libraryPreferences = libraryPreferences,
                playbackPreferences = playbackPreferences,
                localPreferences = localPreferences,
                userPreferencesRepository = userPreferencesRepository,
                instanceRepository = instanceRepository,
                serverConfig = serverConfig,
                authSession = authSession,
                syncRepository = syncRepository,
                rpcCacheInvalidator = rpcCacheInvalidator,
            )
    }

    private fun createFixture(): TestFixture {
        val fixture = TestFixture()

        // Mock StateFlows for local preferences
        every { fixture.localPreferences.themeMode } returns fixture.themeModeFlow
        every { fixture.localPreferences.dynamicColorsEnabled } returns fixture.dynamicColorsFlow
        every { fixture.localPreferences.autoRewindEnabled } returns fixture.autoRewindFlow
        every { fixture.localPreferences.wifiOnlyDownloads } returns fixture.wifiOnlyFlow
        every { fixture.localPreferences.autoRemoveFinished } returns fixture.autoRemoveFlow
        every { fixture.localPreferences.hapticFeedbackEnabled } returns fixture.hapticFeedbackFlow

        // Default stubs for playback preferences - getters
        everySuspend { fixture.playbackPreferences.getDefaultPlaybackSpeed() } returns PlaybackPreferences.DEFAULT_PLAYBACK_SPEED
        everySuspend { fixture.playbackPreferences.getSpatialPlayback() } returns true

        // Default stubs for library preferences - getters
        everySuspend { fixture.libraryPreferences.getIgnoreTitleArticles() } returns true
        everySuspend { fixture.libraryPreferences.getHideSingleBookSeries() } returns true

        // Default stubs for playback preferences - setters (called when syncing from server)
        everySuspend { fixture.playbackPreferences.setDefaultPlaybackSpeed(PlaybackPreferences.DEFAULT_PLAYBACK_SPEED) } returns Unit

        // Default stub for API - return defaults
        everySuspend { fixture.userPreferencesRepository.getPreferences() } returns
            AppResult.Success(
                UserPreferences(
                    defaultPlaybackSpeed = PlaybackPreferences.DEFAULT_PLAYBACK_SPEED,
                    defaultSkipForwardSec = 30,
                    defaultSkipBackwardSec = 10,
                    defaultSleepTimerMin = null,
                    shakeToResetSleepTimer = false,
                ),
            )

        // Default stubs for new dependencies
        everySuspend { fixture.serverConfig.getServerUrl() } returns null
        everySuspend { fixture.instanceRepository.getInstance() } returns
            Failure(Exception("Not configured"))
        everySuspend { fixture.authSession.clearAuthTokens() } returns Unit
        everySuspend { fixture.syncRepository.disconnect() } returns Unit
        everySuspend { fixture.rpcCacheInvalidator.invalidateAll() } returns Unit

        return fixture
    }

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ========== Init / Loading Tests ==========

    @Test
    fun `initial state is loading`() =
        runTest {
            // Given
            val fixture = createFixture()

            // When
            val viewModel = fixture.build()

            // Then - before advanceUntilIdle, should be loading
            assertTrue(viewModel.state.value.isLoading)
        }

    @Test
    fun `loads settings on init`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.playbackPreferences.getDefaultPlaybackSpeed() } returns 1.5f
            everySuspend { fixture.playbackPreferences.getSpatialPlayback() } returns false
            everySuspend { fixture.libraryPreferences.getIgnoreTitleArticles() } returns false
            everySuspend { fixture.libraryPreferences.getHideSingleBookSeries() } returns false
            everySuspend { fixture.playbackPreferences.setDefaultPlaybackSpeed(1.5f) } returns Unit
            everySuspend { fixture.userPreferencesRepository.getPreferences() } returns
                AppResult.Success(
                    UserPreferences(
                        defaultPlaybackSpeed = 1.5f,
                        defaultSkipForwardSec = 30,
                        defaultSkipBackwardSec = 10,
                        defaultSleepTimerMin = null,
                        shakeToResetSleepTimer = false,
                    ),
                )

            // When
            val viewModel = fixture.build()
            advanceUntilIdle()

            // Then
            val state = viewModel.state.value
            assertFalse(state.isLoading)
            assertEquals(1.5f, state.defaultPlaybackSpeed)
            assertFalse(state.spatialPlayback)
            assertFalse(state.ignoreTitleArticles)
            assertFalse(state.hideSingleBookSeries)
        }

    @Test
    fun `uses default values when loading`() =
        runTest {
            // Given
            val fixture = createFixture()

            // When
            val viewModel = fixture.build()
            advanceUntilIdle()

            // Then - should use defaults
            val state = viewModel.state.value
            assertEquals(PlaybackPreferences.DEFAULT_PLAYBACK_SPEED, state.defaultPlaybackSpeed)
            assertTrue(state.spatialPlayback)
            assertTrue(state.ignoreTitleArticles)
            assertTrue(state.hideSingleBookSeries)
        }

    // ========== Playback Speed Tests ==========

    @Test
    fun `setDefaultPlaybackSpeed updates local cache and UI immediately`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.playbackPreferences.setDefaultPlaybackSpeed(1.25f) } returns Unit
            everySuspend { fixture.userPreferencesRepository.setDefaultPlaybackSpeed(1.25f) } returns AppResult.Success(Unit)
            val viewModel = fixture.build()
            advanceUntilIdle()

            // When
            viewModel.setDefaultPlaybackSpeed(1.25f)
            advanceUntilIdle()

            // Then - local cache updated
            verifySuspend { fixture.playbackPreferences.setDefaultPlaybackSpeed(1.25f) }
            assertEquals(1.25f, viewModel.state.value.defaultPlaybackSpeed)
        }

    @Test
    fun `setDefaultPlaybackSpeed syncs to server`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.playbackPreferences.setDefaultPlaybackSpeed(1.5f) } returns Unit
            everySuspend { fixture.userPreferencesRepository.setDefaultPlaybackSpeed(1.5f) } returns AppResult.Success(Unit)
            val viewModel = fixture.build()
            advanceUntilIdle()

            // When
            viewModel.setDefaultPlaybackSpeed(1.5f)
            advanceUntilIdle()

            // Then - server sync called
            verifySuspend { fixture.userPreferencesRepository.setDefaultPlaybackSpeed(1.5f) }
        }

    @Test
    fun `setDefaultPlaybackSpeed continues on server sync failure`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.playbackPreferences.setDefaultPlaybackSpeed(2.0f) } returns Unit
            everySuspend { fixture.userPreferencesRepository.setDefaultPlaybackSpeed(2.0f) } returns
                Failure(Exception("Network error"))
            val viewModel = fixture.build()
            advanceUntilIdle()

            // When
            viewModel.setDefaultPlaybackSpeed(2.0f)
            advanceUntilIdle()

            // Then - UI still updated (optimistic), no error shown
            assertEquals(2.0f, viewModel.state.value.defaultPlaybackSpeed)
        }

    // ========== Spatial Playback Tests ==========

    @Test
    fun `setSpatialPlayback updates setting`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.playbackPreferences.setSpatialPlayback(false) } returns Unit
            val viewModel = fixture.build()
            advanceUntilIdle()

            // When
            viewModel.setSpatialPlayback(false)
            advanceUntilIdle()

            // Then
            verifySuspend { fixture.playbackPreferences.setSpatialPlayback(false) }
            assertFalse(viewModel.state.value.spatialPlayback)
        }

    @Test
    fun `setSpatialPlayback toggles correctly`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.playbackPreferences.getSpatialPlayback() } returns true
            everySuspend { fixture.playbackPreferences.setSpatialPlayback(false) } returns Unit
            val viewModel = fixture.build()
            advanceUntilIdle()
            assertTrue(viewModel.state.value.spatialPlayback)

            // When
            viewModel.setSpatialPlayback(false)
            advanceUntilIdle()

            // Then
            assertFalse(viewModel.state.value.spatialPlayback)
        }

    // ========== Ignore Title Articles Tests ==========

    @Test
    fun `setIgnoreTitleArticles updates setting`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.libraryPreferences.setIgnoreTitleArticles(false) } returns Unit
            val viewModel = fixture.build()
            advanceUntilIdle()

            // When
            viewModel.setIgnoreTitleArticles(false)
            advanceUntilIdle()

            // Then
            verifySuspend { fixture.libraryPreferences.setIgnoreTitleArticles(false) }
            assertFalse(viewModel.state.value.ignoreTitleArticles)
        }

    // ========== Hide Single Book Series Tests ==========

    @Test
    fun `setHideSingleBookSeries updates setting`() =
        runTest {
            // Given
            val fixture = createFixture()
            everySuspend { fixture.libraryPreferences.setHideSingleBookSeries(false) } returns Unit
            val viewModel = fixture.build()
            advanceUntilIdle()

            // When
            viewModel.setHideSingleBookSeries(false)
            advanceUntilIdle()

            // Then
            verifySuspend { fixture.libraryPreferences.setHideSingleBookSeries(false) }
            assertFalse(viewModel.state.value.hideSingleBookSeries)
        }

    @Test
    fun `setHideSingleBookSeries can be enabled`() =
        runTest {
            // Given - start with false
            val fixture = createFixture()
            everySuspend { fixture.libraryPreferences.getHideSingleBookSeries() } returns false
            everySuspend { fixture.libraryPreferences.setHideSingleBookSeries(true) } returns Unit
            val viewModel = fixture.build()
            advanceUntilIdle()
            assertFalse(viewModel.state.value.hideSingleBookSeries)

            // When
            viewModel.setHideSingleBookSeries(true)
            advanceUntilIdle()

            // Then
            assertTrue(viewModel.state.value.hideSingleBookSeries)
        }

    @Test
    fun `signOut stops the sync engine before clearing tokens`() =
        runTest {
            // Given
            val fixture = createFixture()
            val viewModel = fixture.build()
            advanceUntilIdle()

            // When
            viewModel.signOut()
            advanceUntilIdle()

            // Then — the engine is stopped (no reconnect loop), cached RPC connections
            // are dropped (no cross-user reuse), and tokens are cleared.
            verifySuspend { fixture.syncRepository.disconnect() }
            verifySuspend { fixture.rpcCacheInvalidator.invalidateAll() }
            verifySuspend { fixture.authSession.clearAuthTokens() }
        }
}
