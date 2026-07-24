package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.domain.model.ServerSettings
import com.calypsan.listenup.client.domain.usecase.admin.LoadServerSettingsUseCase
import com.calypsan.listenup.client.domain.usecase.admin.UpdateServerSettingsUseCase
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.calypsan.listenup.core.error.ErrorBus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Tests for AdminSettingsViewModel.
 *
 * Tests cover:
 * - Initial `Loading` state before the load completes
 * - `Ready` emission once settings are loaded (serverName + remoteUrl from getServerSettings)
 * - `Error` state when the initial load fails
 * - Edit-buffer mutations (`setServerName`, `setRemoteUrl`) update Ready and recompute `isDirty`
 * - `saveAll` happy path clears `isSaving` and resets `isDirty` for server name
 * - `saveAll` happy path clears `isSaving` and resets `isDirty` for remote URL
 * - `saveAll` failure surfaces as transient `error` on Ready
 * - `clearError` clears the transient error on Ready
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminSettingsViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        // ========== Test Fixtures ==========

        // createServerSettings is declared before TestFixture so createFixture's default arg resolves.
        fun createServerSettings(
            serverName: String = "My Server",
            remoteUrl: String? = null,
            inboxEnabled: Boolean = false,
        ): ServerSettings =
            ServerSettings(
                serverName = serverName,
                remoteUrl = remoteUrl,
                inboxEnabled = inboxEnabled,
            )

        class TestFixture {
            val loadServerSettingsUseCase: LoadServerSettingsUseCase = mock()
            val updateServerSettingsUseCase: UpdateServerSettingsUseCase = mock()

            fun build(): AdminSettingsViewModel =
                AdminSettingsViewModel(
                    loadServerSettingsUseCase = loadServerSettingsUseCase,
                    updateServerSettingsUseCase = updateServerSettingsUseCase,
                    errorBus = ErrorBus(),
                )
        }

        fun createFixture(settings: ServerSettings = createServerSettings()): TestFixture {
            val fixture = TestFixture()
            everySuspend { fixture.loadServerSettingsUseCase() } returns AppResult.Success(settings)
            return fixture
        }

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        // ========== Initial State ==========

        test("initial state is Loading") {
            runTest {
                val fixture = createFixture()
                // Do not advance — we want to observe the state before init completes.
                val viewModel = fixture.build()

                viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Loading>()
            }
        }

        // ========== Load ==========

        test("load transitions to Ready with server settings") {
            runTest {
                val fixture =
                    createFixture(
                        settings = createServerSettings(serverName = "ListenUp Prod", remoteUrl = "https://audio.example.com"),
                    )

                val viewModel = fixture.build()
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                ready.serverName shouldBe "ListenUp Prod"
                ready.remoteUrl shouldBe "https://audio.example.com"
                ready.isDirty shouldBe false
                ready.isSaving shouldBe false
                ready.error shouldBe null
            }
        }

        test("load failure transitions to Error") {
            runTest {
                val fixture = TestFixture()
                // Body-level message convention: pass a typed AppError so the
                // user-facing message survives delegation to the ViewModel.
                everySuspend { fixture.loadServerSettingsUseCase() } returns
                    AppResult.Failure(
                        com.calypsan.listenup.api.error
                            .ValidationError(message = "Network down"),
                    )

                val viewModel = fixture.build()
                advanceUntilIdle()

                val error = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Error>()
                error.error.message shouldBe "Network down"
            }
        }

        // ========== Edit Buffer Mutations ==========

        test("setServerName updates Ready and marks dirty") {
            runTest {
                val fixture = createFixture(settings = createServerSettings(serverName = "Old Name"))
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.setServerName("New Name")

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                ready.serverName shouldBe "New Name"
                ready.isDirty shouldBe true
            }
        }

        test("setInboxEnabled persists immediately and does not mark dirty") {
            runTest {
                val fixture = createFixture(settings = createServerSettings(inboxEnabled = false))
                everySuspend { fixture.updateServerSettingsUseCase.updateInboxEnabled(true) } returns
                    AppResult.Success(createServerSettings(inboxEnabled = true))
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.setInboxEnabled(true)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                ready.inboxEnabled shouldBe true
                // A switch applies on tap — no Save needed, so it never enters the dirty/Save-FAB state.
                ready.isDirty shouldBe false
                verifySuspend(VerifyMode.atLeast(1)) {
                    fixture.updateServerSettingsUseCase.updateInboxEnabled(true)
                }
            }
        }

        test("setInboxEnabled failure reverts the toggle and surfaces the error") {
            runTest {
                val fixture = createFixture(settings = createServerSettings(inboxEnabled = false))
                everySuspend { fixture.updateServerSettingsUseCase.updateInboxEnabled(true) } returns
                    AppResult.Failure(
                        com.calypsan.listenup.api.error
                            .ValidationError(message = "Forbidden"),
                    )
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.setInboxEnabled(true)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                // The optimistic flip reverts to the server-confirmed value on failure.
                ready.inboxEnabled shouldBe false
                (ready.error?.message?.contains("Forbidden") == true) shouldBe true
            }
        }

        // ========== Save ==========

        test("saveAll happy-path persists server name changes and resets dirty") {
            runTest {
                val fixture = createFixture(settings = createServerSettings(serverName = "Original"))
                everySuspend { fixture.updateServerSettingsUseCase.updateServerName("Renamed") } returns
                    AppResult.Success(createServerSettings(serverName = "Renamed"))
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.setServerName("Renamed")
                viewModel.saveAll()
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                ready.serverName shouldBe "Renamed"
                ready.isSaving shouldBe false
                ready.isDirty shouldBe false
                ready.error shouldBe null
                verifySuspend(VerifyMode.atLeast(1)) {
                    fixture.updateServerSettingsUseCase.updateServerName("Renamed")
                }
            }
        }

        test("saveAll happy-path persists remote URL changes and resets dirty") {
            runTest {
                val fixture = createFixture(settings = createServerSettings(remoteUrl = "https://old.example.com"))
                everySuspend { fixture.updateServerSettingsUseCase.updateRemoteUrl("https://new.example.com") } returns
                    AppResult.Success(createServerSettings(remoteUrl = "https://new.example.com"))
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.setRemoteUrl("https://new.example.com")
                viewModel.saveAll()
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                ready.isSaving shouldBe false
                ready.isDirty shouldBe false
                ready.error shouldBe null
                verifySuspend(VerifyMode.atLeast(1)) {
                    fixture.updateServerSettingsUseCase.updateRemoteUrl("https://new.example.com")
                }
            }
        }

        test("saveAll failure surfaces as transient error on Ready") {
            runTest {
                val fixture = createFixture(settings = createServerSettings(serverName = "Original"))
                // Body-level message convention: pass a typed AppError so the
                // "Forbidden" text surfaces directly as the typed Ready.error.
                everySuspend { fixture.updateServerSettingsUseCase.updateServerName("Renamed") } returns
                    AppResult.Failure(
                        com.calypsan.listenup.api.error
                            .ValidationError(message = "Forbidden"),
                    )
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.setServerName("Renamed")
                viewModel.saveAll()
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                ready.isSaving shouldBe false
                (ready.error?.message?.contains("Forbidden") == true) shouldBe true
                // Dirty remains true because buffer diverges from baseline after failed save.
                ready.isDirty shouldBe true
            }
        }

        // ========== Transient State Clearing ==========

        test("clearError clears Ready error to null") {
            runTest {
                val fixture = createFixture(settings = createServerSettings(serverName = "Original"))
                everySuspend { fixture.updateServerSettingsUseCase.updateServerName("Renamed") } returns
                    Failure(RuntimeException("boom"))
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.setServerName("Renamed")
                viewModel.saveAll()
                advanceUntilIdle()
                val withError = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                (withError.error != null) shouldBe true

                viewModel.clearError()

                val cleared = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                cleared.error shouldBe null
            }
        }
    })
