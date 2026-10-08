package com.calypsan.listenup.client.presentation.admin

import com.calypsan.listenup.api.dto.admin.HardcoverApiTokenStatus
import com.calypsan.listenup.api.dto.admin.HardcoverSourceStatus
import com.calypsan.listenup.api.dto.admin.RatingSourceStatus
import com.calypsan.listenup.api.error.HardcoverError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.ExternalRatingSource
import com.calypsan.listenup.client.core.Failure
import com.calypsan.listenup.client.domain.model.ServerSettings
import com.calypsan.listenup.client.domain.usecase.admin.LoadServerSettingsUseCase
import com.calypsan.listenup.client.domain.usecase.admin.UpdateServerSettingsUseCase
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CompletableDeferred
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
            holdNewBooksForReview: Boolean = false,
            pushNotificationsEnabled: Boolean = true,
        ): ServerSettings =
            ServerSettings(
                serverName = serverName,
                remoteUrl = remoteUrl,
                holdNewBooksForReview = holdNewBooksForReview,
                pushNotificationsEnabled = pushNotificationsEnabled,
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

        fun createFixture(
            settings: ServerSettings = createServerSettings(),
            ratingSources: List<RatingSourceStatus> = emptyList(),
            hardcoverSource: HardcoverSourceStatus = HardcoverSourceStatus(),
        ): TestFixture {
            val fixture = TestFixture()
            everySuspend { fixture.loadServerSettingsUseCase() } returns AppResult.Success(settings)
            everySuspend { fixture.loadServerSettingsUseCase.ratingSources() } returns AppResult.Success(ratingSources)
            everySuspend { fixture.loadServerSettingsUseCase.hardcoverSource() } returns AppResult.Success(hardcoverSource)
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
                        settings =
                            createServerSettings(
                                serverName = "ListenUp Prod",
                                remoteUrl = "https://audio.example.com",
                                pushNotificationsEnabled = false,
                            ),
                    )

                val viewModel = fixture.build()
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                ready.serverName shouldBe "ListenUp Prod"
                ready.remoteUrl shouldBe "https://audio.example.com"
                ready.pushNotificationsEnabled shouldBe false
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

        test("setHoldNewBooksForReview persists immediately and does not mark dirty") {
            runTest {
                val fixture = createFixture(settings = createServerSettings(holdNewBooksForReview = false))
                everySuspend { fixture.updateServerSettingsUseCase.updateHoldNewBooksForReview(true) } returns
                    AppResult.Success(createServerSettings(holdNewBooksForReview = true))
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.setHoldNewBooksForReview(true)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                ready.holdNewBooksForReview shouldBe true
                // A switch applies on tap — no Save needed, so it never enters the dirty/Save-FAB state.
                ready.isDirty shouldBe false
                verifySuspend(VerifyMode.atLeast(1)) {
                    fixture.updateServerSettingsUseCase.updateHoldNewBooksForReview(true)
                }
            }
        }

        test("setHoldNewBooksForReview failure reverts the toggle and surfaces the error") {
            runTest {
                val fixture = createFixture(settings = createServerSettings(holdNewBooksForReview = false))
                everySuspend { fixture.updateServerSettingsUseCase.updateHoldNewBooksForReview(true) } returns
                    AppResult.Failure(
                        com.calypsan.listenup.api.error
                            .ValidationError(message = "Forbidden"),
                    )
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.setHoldNewBooksForReview(true)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                // The optimistic flip reverts to the server-confirmed value on failure.
                ready.holdNewBooksForReview shouldBe false
                (ready.error?.run { message.contains("Forbidden") } == true) shouldBe true
            }
        }

        test("setPushNotificationsEnabled persists immediately and does not mark dirty") {
            runTest {
                val fixture = createFixture(settings = createServerSettings(pushNotificationsEnabled = false))
                everySuspend { fixture.updateServerSettingsUseCase.updatePushNotificationsEnabled(true) } returns
                    AppResult.Success(createServerSettings(pushNotificationsEnabled = true))
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.setPushNotificationsEnabled(true)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                ready.pushNotificationsEnabled shouldBe true
                // A switch applies on tap — no Save needed, so it never enters the dirty/Save-FAB state.
                ready.isDirty shouldBe false
                verifySuspend(VerifyMode.atLeast(1)) {
                    fixture.updateServerSettingsUseCase.updatePushNotificationsEnabled(true)
                }
            }
        }

        test("setPushNotificationsEnabled failure reverts the toggle and surfaces the error") {
            runTest {
                val fixture = createFixture(settings = createServerSettings(pushNotificationsEnabled = true))
                everySuspend { fixture.updateServerSettingsUseCase.updatePushNotificationsEnabled(false) } returns
                    AppResult.Failure(
                        com.calypsan.listenup.api.error
                            .ValidationError(message = "Forbidden"),
                    )
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.setPushNotificationsEnabled(false)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                // The optimistic flip reverts to the server-confirmed value on failure.
                ready.pushNotificationsEnabled shouldBe true
                (ready.error?.run { message.contains("Forbidden") } == true) shouldBe true
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
                (ready.error?.run { message.contains("Forbidden") } == true) shouldBe true
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

        // ========== Rating Sources ==========

        test("load populates ratingSources alongside the rest of the settings") {
            runTest {
                val sources =
                    listOf(
                        RatingSourceStatus(ExternalRatingSource.AUDIBLE, enabled = true, lastFetchedAt = 1L, lastError = null),
                    )
                val fixture = createFixture(ratingSources = sources)
                val viewModel = fixture.build()
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                ready.ratingSources shouldBe sources
            }
        }

        test("setRatingSourceEnabled persists immediately and does not mark dirty") {
            runTest {
                val before = RatingSourceStatus(ExternalRatingSource.AUDIBLE, enabled = true, lastFetchedAt = null, lastError = null)
                val after = before.copy(enabled = false)
                val fixture = createFixture(ratingSources = listOf(before))
                everySuspend {
                    fixture.updateServerSettingsUseCase.setRatingSourceEnabled(ExternalRatingSource.AUDIBLE, false)
                } returns AppResult.Success(listOf(after))
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.setRatingSourceEnabled(ExternalRatingSource.AUDIBLE, false)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                ready.ratingSources shouldBe listOf(after)
                ready.isDirty shouldBe false
                verifySuspend(VerifyMode.atLeast(1)) {
                    fixture.updateServerSettingsUseCase.setRatingSourceEnabled(ExternalRatingSource.AUDIBLE, false)
                }
            }
        }

        test("setRatingSourceEnabled failure reverts the toggle and surfaces the error") {
            runTest {
                val before = RatingSourceStatus(ExternalRatingSource.AUDIBLE, enabled = true, lastFetchedAt = null, lastError = null)
                val fixture = createFixture(ratingSources = listOf(before))
                everySuspend {
                    fixture.updateServerSettingsUseCase.setRatingSourceEnabled(ExternalRatingSource.AUDIBLE, false)
                } returns
                    AppResult.Failure(
                        com.calypsan.listenup.api.error
                            .ValidationError(message = "Forbidden"),
                    )
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.setRatingSourceEnabled(ExternalRatingSource.AUDIBLE, false)
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                // The optimistic flip reverts to the server-confirmed list on failure.
                ready.ratingSources shouldBe listOf(before)
                (ready.error?.run { message.contains("Forbidden") } == true) shouldBe true
            }
        }

        // ========== Admin → Hardcover (#1542) ==========

        val savedToken = HardcoverSourceStatus(apiToken = HardcoverApiTokenStatus.Saved("simon", 1L))

        test("the Hardcover section loads alongside the settings") {
            runTest {
                val fixture = createFixture(hardcoverSource = savedToken)

                val viewModel = fixture.build()
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                ready.hardcoverSource shouldBe savedToken
                ready.hardcoverTokenSave shouldBe HardcoverTokenSave.Idle
            }
        }

        test("saving a token is Busy while Hardcover checks it, then shows its owner — and the token is kept nowhere") {
            runTest {
                val fixture = createFixture()
                val answer = CompletableDeferred<AppResult<HardcoverSourceStatus>>()
                everySuspend { fixture.updateServerSettingsUseCase.setHardcoverApiToken("hc_vm_test_token") } calls { answer.await() }
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.saveHardcoverApiToken("hc_vm_test_token")
                advanceUntilIdle()
                viewModel.state.value
                    .shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                    .hardcoverTokenSave shouldBe HardcoverTokenSave.Busy

                answer.complete(AppResult.Success(savedToken))
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                ready.hardcoverSource shouldBe savedToken
                ready.hardcoverTokenSave shouldBe HardcoverTokenSave.Idle
                ready.toString() shouldNotContain "hc_vm_test_token"
            }
        }

        test("a token Hardcover refuses is shown beside the field, and the section is unchanged") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.updateServerSettingsUseCase.setHardcoverApiToken(any()) } returns
                    AppResult.Failure(HardcoverError.TokenRejected())
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.saveHardcoverApiToken("hc_wrong_test_token")
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                ready.hardcoverTokenSave shouldBe HardcoverTokenSave.Refused(HardcoverError.TokenRejected())
                ready.hardcoverSource shouldBe HardcoverSourceStatus()
                ready.error shouldBe null
            }
        }

        test("editing the field again clears the refusal") {
            runTest {
                val fixture = createFixture()
                everySuspend { fixture.updateServerSettingsUseCase.setHardcoverApiToken(any()) } returns
                    AppResult.Failure(HardcoverError.TokenRejected())
                val viewModel = fixture.build()
                advanceUntilIdle()
                viewModel.saveHardcoverApiToken("hc_wrong_test_token")
                advanceUntilIdle()

                viewModel.clearHardcoverTokenError()

                viewModel.state.value
                    .shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                    .hardcoverTokenSave shouldBe HardcoverTokenSave.Idle
            }
        }

        test("a blank token is never sent") {
            runTest {
                val fixture = createFixture()
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.saveHardcoverApiToken("   ")
                advanceUntilIdle()

                verifySuspend(VerifyMode.not) { fixture.updateServerSettingsUseCase.setHardcoverApiToken(any()) }
            }
        }

        test("Remove clears the token") {
            runTest {
                val fixture = createFixture(hardcoverSource = savedToken)
                everySuspend { fixture.updateServerSettingsUseCase.clearHardcoverApiToken() } returns
                    AppResult.Success(HardcoverSourceStatus())
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.removeHardcoverApiToken()
                advanceUntilIdle()

                viewModel.state.value
                    .shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                    .hardcoverSource shouldBe HardcoverSourceStatus()
            }
        }

        test("the metadata switch flips at once, and flips back when the server refuses") {
            runTest {
                val fixture = createFixture()
                val answer = CompletableDeferred<AppResult<HardcoverSourceStatus>>()
                everySuspend { fixture.updateServerSettingsUseCase.setHardcoverMetadataEnabled(false) } calls { answer.await() }
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.setHardcoverMetadataEnabled(false)
                viewModel.state.value
                    .shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                    .hardcoverSource
                    ?.metadataEnabled shouldBe false

                answer.complete(AppResult.Failure(HardcoverError.Unavailable()))
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                ready.hardcoverSource?.metadataEnabled shouldBe true
                ready.error shouldBe HardcoverError.Unavailable()
            }
        }

        test("the store region loads, and a new one saves at once without marking the form dirty") {
            runTest {
                val fixture = createFixture(settings = createServerSettings().copy(metadataRegion = "uk"))
                everySuspend { fixture.updateServerSettingsUseCase.updateMetadataRegion("au") } returns
                    AppResult.Success(createServerSettings().copy(metadataRegion = "au"))
                val viewModel = fixture.build()
                advanceUntilIdle()
                viewModel.state.value
                    .shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                    .metadataRegion shouldBe "uk"

                viewModel.setMetadataRegion("au")
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                ready.metadataRegion shouldBe "au"
                ready.isDirty shouldBe false
                verifySuspend { fixture.updateServerSettingsUseCase.updateMetadataRegion("au") }
            }
        }

        test("a refused store region reverts to the saved one and says why") {
            runTest {
                val fixture = createFixture(settings = createServerSettings().copy(metadataRegion = "uk"))
                everySuspend { fixture.updateServerSettingsUseCase.updateMetadataRegion("au") } returns
                    AppResult.Failure(
                        com.calypsan.listenup.api.error
                            .ValidationError(message = "Forbidden"),
                    )
                val viewModel = fixture.build()
                advanceUntilIdle()

                viewModel.setMetadataRegion("au")
                advanceUntilIdle()

                val ready = viewModel.state.value.shouldBeInstanceOf<AdminSettingsUiState.Ready>()
                ready.metadataRegion shouldBe "uk"
                (ready.error?.run { message.contains("Forbidden") } == true) shouldBe true
            }
        }
    })
