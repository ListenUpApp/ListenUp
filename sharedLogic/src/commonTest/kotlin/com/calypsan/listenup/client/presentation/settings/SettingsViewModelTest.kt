package com.calypsan.listenup.client.presentation.settings

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.ThemeMode
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.LibraryPreferences
import com.calypsan.listenup.client.domain.repository.LocalPreferences
import com.calypsan.listenup.client.domain.repository.PlaybackPreferences
import com.calypsan.listenup.client.data.remote.RpcCacheInvalidator
import com.calypsan.listenup.client.domain.repository.PushRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserPreferences
import com.calypsan.listenup.client.domain.repository.UserPreferencesRepository
import com.calypsan.listenup.core.error.ErrorBus
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
class SettingsViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        // ========== Test Fixture Builder ==========

        class TestFixture {
            val libraryPreferences: LibraryPreferences = mock()
            val playbackPreferences: PlaybackPreferences = mock()
            val localPreferences: LocalPreferences = mock()
            val userPreferencesRepository: UserPreferencesRepository = mock()
            val instanceRepository: InstanceRepository = mock()
            val serverConfig: ServerConfig = mock()
            val authSession: AuthSession = mock()
            val syncRepository: SyncRepository = mock()
            val rpcCacheInvalidator: RpcCacheInvalidator = mock()
            val pushRepository: PushRepository = mock()
            val errorBus: ErrorBus = ErrorBus()

            // StateFlows for local preferences (mocked as MutableStateFlow)
            val themeModeFlow = MutableStateFlow(ThemeMode.SYSTEM)
            val dynamicColorsFlow = MutableStateFlow(true)
            val autoRewindFlow = MutableStateFlow(true)
            val wifiOnlyFlow = MutableStateFlow(true)
            val autoRemoveFlow = MutableStateFlow(false)
            val hapticFeedbackFlow = MutableStateFlow(true)

            // The synced-preferences source the VM now observes (Room-backed in production). Tests
            // push new values here to simulate the repository's optimistic/firehose write-through.
            val syncedPreferencesFlow =
                MutableStateFlow(
                    UserPreferences(
                        defaultPlaybackSpeed = PlaybackPreferences.DEFAULT_PLAYBACK_SPEED,
                        defaultSkipForwardSec = 30,
                        defaultSkipBackwardSec = 10,
                        defaultSleepTimerMin = null,
                        shakeToResetSleepTimer = false,
                    ),
                )

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
                    pushRepository = pushRepository,
                    errorBus = errorBus,
                )
        }

        fun createFixture(): TestFixture {
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
            everySuspend { fixture.playbackPreferences.getDefaultSkipForwardSec() } returns
                PlaybackPreferences.DEFAULT_SKIP_FORWARD_SEC
            everySuspend { fixture.playbackPreferences.getDefaultSkipBackwardSec() } returns
                PlaybackPreferences.DEFAULT_SKIP_BACKWARD_SEC

            // Default stubs for library preferences - getters
            everySuspend { fixture.libraryPreferences.getIgnoreTitleArticles() } returns true
            everySuspend { fixture.libraryPreferences.getHideSingleBookSeries() } returns true

            // Default stubs for playback preferences - setters (called when syncing from server)
            everySuspend { fixture.playbackPreferences.setDefaultPlaybackSpeed(PlaybackPreferences.DEFAULT_PLAYBACK_SPEED) } returns Unit
            everySuspend { fixture.playbackPreferences.setDefaultSkipForwardSec(any()) } returns Unit
            everySuspend { fixture.playbackPreferences.setDefaultSkipBackwardSec(any()) } returns Unit

            // The VM observes synced preferences reactively; the fixture flow is the source of truth.
            every { fixture.userPreferencesRepository.observePreferences() } returns fixture.syncedPreferencesFlow

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
            everySuspend { fixture.instanceRepository.getServerInfo() } returns
                Failure(Exception("Not configured"))
            everySuspend { fixture.authSession.clearAuthTokens() } returns Unit
            everySuspend { fixture.syncRepository.disconnect() } returns Unit
            everySuspend { fixture.rpcCacheInvalidator.invalidateAll() } returns Unit
            everySuspend { fixture.pushRepository.sendTestNotification() } returns AppResult.Success(Unit)

            return fixture
        }

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        // ========== Init / Loading Tests ==========

        test("initial state is loading") {
            runTest {
                // Given
                val fixture = createFixture()

                // When
                val viewModel = fixture.build()

                // Then - before advanceUntilIdle, should be loading
                viewModel.state.value.isLoading shouldBe true
            }
        }

        test("loads settings on init") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.libraryPreferences.getIgnoreTitleArticles() } returns false
                everySuspend { fixture.libraryPreferences.getHideSingleBookSeries() } returns false
                everySuspend { fixture.playbackPreferences.setDefaultPlaybackSpeed(1.5f) } returns Unit
                // The synced playback speed now flows from the observed (Room-backed) preferences.
                fixture.syncedPreferencesFlow.value =
                    fixture.syncedPreferencesFlow.value.copy(defaultPlaybackSpeed = 1.5f)
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
                state.isLoading shouldBe false
                state.defaultPlaybackSpeed shouldBe 1.5f
                state.ignoreTitleArticles shouldBe false
                state.hideSingleBookSeries shouldBe false
            }
        }

        test("uses default values when loading") {
            runTest {
                // Given
                val fixture = createFixture()

                // When
                val viewModel = fixture.build()
                advanceUntilIdle()

                // Then - should use defaults
                val state = viewModel.state.value
                state.defaultPlaybackSpeed shouldBe PlaybackPreferences.DEFAULT_PLAYBACK_SPEED
                state.ignoreTitleArticles shouldBe true
                state.hideSingleBookSeries shouldBe true
            }
        }

        // ========== Playback Speed Tests ==========

        test("setDefaultPlaybackSpeed updates local cache and UI immediately") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.playbackPreferences.setDefaultPlaybackSpeed(1.25f) } returns Unit
                // The repository's optimistic Room write drives the observed flow — simulate it here.
                everySuspend { fixture.userPreferencesRepository.setDefaultPlaybackSpeed(1.25f) } calls {
                    fixture.syncedPreferencesFlow.value =
                        fixture.syncedPreferencesFlow.value.copy(defaultPlaybackSpeed = 1.25f)
                    AppResult.Success(Unit)
                }
                val viewModel = fixture.build()
                advanceUntilIdle()

                // When
                viewModel.setDefaultPlaybackSpeed(1.25f)
                advanceUntilIdle()

                // Then - player store mirrored and the UI reflects the value via the observed flow.
                verifySuspend { fixture.playbackPreferences.setDefaultPlaybackSpeed(1.25f) }
                viewModel.state.value.defaultPlaybackSpeed shouldBe 1.25f
            }
        }

        test("setDefaultPlaybackSpeed syncs to server") {
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
        }

        test("setDefaultPlaybackSpeed continues on server sync failure") {
            runTest {
                // Given
                val fixture = createFixture()
                everySuspend { fixture.playbackPreferences.setDefaultPlaybackSpeed(2.0f) } returns Unit
                // Even when the server push fails, the repository's optimistic Room write already
                // landed — so the observed flow (and thus the UI) keeps the new value.
                everySuspend { fixture.userPreferencesRepository.setDefaultPlaybackSpeed(2.0f) } calls {
                    fixture.syncedPreferencesFlow.value =
                        fixture.syncedPreferencesFlow.value.copy(defaultPlaybackSpeed = 2.0f)
                    Failure(Exception("Network error"))
                }
                val viewModel = fixture.build()
                advanceUntilIdle()

                // When
                viewModel.setDefaultPlaybackSpeed(2.0f)
                advanceUntilIdle()

                // Then - UI still updated (optimistic), no error shown
                viewModel.state.value.defaultPlaybackSpeed shouldBe 2.0f
            }
        }

        // ========== Ignore Title Articles Tests ==========

        test("setIgnoreTitleArticles updates setting") {
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
                viewModel.state.value.ignoreTitleArticles shouldBe false
            }
        }

        // ========== Hide Single Book Series Tests ==========

        test("setHideSingleBookSeries updates setting") {
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
                viewModel.state.value.hideSingleBookSeries shouldBe false
            }
        }

        test("setHideSingleBookSeries can be enabled") {
            runTest {
                // Given - start with false
                val fixture = createFixture()
                everySuspend { fixture.libraryPreferences.getHideSingleBookSeries() } returns false
                everySuspend { fixture.libraryPreferences.setHideSingleBookSeries(true) } returns Unit
                val viewModel = fixture.build()
                advanceUntilIdle()
                viewModel.state.value.hideSingleBookSeries shouldBe false

                // When
                viewModel.setHideSingleBookSeries(true)
                advanceUntilIdle()

                // Then
                viewModel.state.value.hideSingleBookSeries shouldBe true
            }
        }

        // ========== Push Notifications ==========

        test("loads pushEnabled from server info") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.instanceRepository.getServerInfo() } returns
                    AppResult.Success(
                        ServerInfo(
                            name = "ListenUp",
                            version = "1.0.0",
                            apiVersion = "v1",
                            setupRequired = false,
                            registrationPolicy = RegistrationPolicy.CLOSED,
                            pushEnabled = true,
                            instanceId = "instance-1",
                        ),
                    )

                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.state.value.pushEnabled shouldBe true
            }
        }

        test("sendTestNotification delegates to PushRepository") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.sendTestNotification()
                advanceUntilIdle()

                verifySuspend { fixture.pushRepository.sendTestNotification() }
            }
        }

        test("sendTestNotification failure emits to the error bus") {
            runTest {
                val fixture = createFixture()
                val error =
                    com.calypsan.listenup.api.error
                        .ValidationError(message = "Push notifications are not enabled on this server.")
                everySuspend { fixture.pushRepository.sendTestNotification() } returns AppResult.Failure(error)
                val viewModel = fixture.build()
                advanceUntilIdle()

                fixture.errorBus.errors.test {
                    viewModel.sendTestNotification()
                    advanceUntilIdle()
                    awaitItem() shouldBe error
                }
            }
        }

        test("signOut stops the sync engine before clearing tokens") {
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
    })
