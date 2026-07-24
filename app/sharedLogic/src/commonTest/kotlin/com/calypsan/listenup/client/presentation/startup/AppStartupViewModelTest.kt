package com.calypsan.listenup.client.presentation.startup

import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.dto.SetupStatus
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.answering.throws
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Tests for AppStartupViewModel.
 *
 * Tests cover:
 * - Initial state and library setup check
 * - onAppBackgrounded() records timestamp
 * - onAppForegrounded() behavior for short vs long background periods
 * - Threshold constant value
 *
 * Uses Mokkery for mocking and follows Given-When-Then style.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppStartupViewModelTest :
    FunSpec({
        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        // ========== Test Data Factories ==========

        fun createMockUserRepository(): UserRepository = mock<UserRepository>()

        // readiness derives from the sync layer's scan signal; these tests don't drive a scan, so a
        // not-scanning stub is all the aggregator needs. hasLocalLibrary() defaults to false so that
        // existing failure tests still expect CheckFailed — only tests that explicitly opt in to
        // hasLocalLibrary() = true will observe the offline-fallback path.
        fun createMockSyncRepository(hasLocalLibrary: Boolean = false): SyncRepository {
            val sync = mock<SyncRepository>()
            every { sync.isBuildingInitialLibrary } returns MutableStateFlow(false)
            every { sync.scanProgress } returns MutableStateFlow(null)
            everySuspend { sync.hasLocalLibrary() } returns hasLocalLibrary
            return sync
        }

        fun libraryAdminChannel(service: LibraryAdminService = mock()): RpcChannel<LibraryAdminService> = RpcChannel.forTest(service)

        // The setup check now runs off authState transitions to Authenticated, so the VM needs an
        // AuthSession. Default to already-Authenticated so existing tests exercise the check as before.
        fun createMockAuthSession(
            authState: AuthState = AuthState.Authenticated(UserId("user-001"), SessionId("session-001")),
        ): AuthSession {
            val session = mock<AuthSession>()
            every { session.authState } returns MutableStateFlow(authState)
            return session
        }

        fun createTestUser(
            id: String = "user-001",
            isAdmin: Boolean = false,
        ): User =
            User(
                id =
                    UserId(id),
                email = "test@example.com",
                displayName = "Test User",
                firstName = null,
                lastName = null,
                isAdmin = isAdmin,
                tagline = null,
                createdAtMs = 1704067200000L,
                updatedAtMs = 1704153600000L,
            )

        // ========== Threshold Constant Tests ==========

        test("BACKGROUND_THRESHOLD_MS is 30 minutes") {
            // Given/When/Then
            val expectedMs = 30 * 60 * 1000L
            AppStartupViewModel.BACKGROUND_THRESHOLD_MS shouldBe expectedMs
        }

        // ========== Initial State Tests ==========

        test("initial state has isChecking true") {
            runTest {
                // Given
                val userRepository = createMockUserRepository()
                val channel = libraryAdminChannel()
                everySuspend { userRepository.refreshCurrentUser() } returns null
                everySuspend { userRepository.getCurrentUser() } returns null

                // When
                val viewModel = AppStartupViewModel(userRepository, channel, createMockAuthSession(), createMockSyncRepository())

                // Then - initial state before coroutine completes
                viewModel.state.value.isChecking shouldBe true
            }
        }

        test("initial check completes and sets isChecking false for non-admin user") {
            runTest {
                // Given
                val userRepository = createMockUserRepository()
                val channel = libraryAdminChannel()
                val regularUser = createTestUser(isAdmin = false)
                everySuspend { userRepository.refreshCurrentUser() } returns regularUser
                everySuspend { userRepository.getCurrentUser() } returns regularUser

                // When
                val viewModel = AppStartupViewModel(userRepository, channel, createMockAuthSession(), createMockSyncRepository())
                advanceUntilIdle()

                // Then
                viewModel.state.value.isChecking shouldBe false
                viewModel.state.value.needsLibrarySetup shouldBe false
            }
        }

        test("initial check completes and sets needsLibrarySetup true for admin when library needs setup") {
            runTest {
                // Given
                val userRepository = createMockUserRepository()
                val service = mock<LibraryAdminService>()
                val channel = libraryAdminChannel(service)
                val adminUser = createTestUser(isAdmin = true)
                everySuspend { userRepository.refreshCurrentUser() } returns adminUser
                everySuspend { userRepository.getCurrentUser() } returns adminUser
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = true))

                // When
                val viewModel = AppStartupViewModel(userRepository, channel, createMockAuthSession(), createMockSyncRepository())
                advanceUntilIdle()

                // Then
                viewModel.state.value.isChecking shouldBe false
                viewModel.state.value.needsLibrarySetup shouldBe true
            }
        }

        // Regression: the VM is created eagerly at app launch (before login). The setup check
        // must NOT run at construction against a null/unauthenticated user — it must run when
        // authState becomes Authenticated, and re-run on that transition. Before the fix, the
        // init-time check resolved a null user, cached "no setup needed", and never re-ran after
        // the user registered, stranding the admin on the homepage instead of the wizard.
        test("setup check runs on transition to Authenticated, not at construction") {
            runTest {
                // Given - admin + a library that needs setup, but NOT yet authenticated
                val userRepository = createMockUserRepository()
                val service = mock<LibraryAdminService>()
                val channel = libraryAdminChannel(service)
                val adminUser = createTestUser(isAdmin = true)
                everySuspend { userRepository.refreshCurrentUser() } returns adminUser
                everySuspend { userRepository.getCurrentUser() } returns adminUser
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = true))
                val authState = MutableStateFlow<AuthState>(AuthState.NeedsLogin(openRegistration = false))
                val authSession = mock<AuthSession>()
                every { authSession.authState } returns authState

                // When - constructed before login (as MainActivity does at app launch)
                val viewModel = AppStartupViewModel(userRepository, channel, authSession, createMockSyncRepository())
                advanceUntilIdle()

                // Then - no premature check; not dropped into the wizard
                viewModel.state.value.isChecking shouldBe false
                viewModel.state.value.needsLibrarySetup shouldBe false

                // When - the user authenticates (creates the account)
                authState.value = AuthState.Authenticated(UserId("user-001"), SessionId("session-001"))
                advanceUntilIdle()

                // Then - the check runs against the authenticated admin → routes to the wizard
                viewModel.state.value.isChecking shouldBe false
                viewModel.state.value.needsLibrarySetup shouldBe true
            }
        }

        // ========== onAppBackgrounded Tests ==========

        test("onAppBackgrounded records timestamp") {
            runTest {
                // Given
                val userRepository = createMockUserRepository()
                val channel = libraryAdminChannel()
                everySuspend { userRepository.refreshCurrentUser() } returns null
                everySuspend { userRepository.getCurrentUser() } returns null
                val viewModel = AppStartupViewModel(userRepository, channel, createMockAuthSession(), createMockSyncRepository())
                advanceUntilIdle()

                // When
                viewModel.onAppBackgrounded()

                // Then
                viewModel.state.value.backgroundedAtMs
                    .shouldNotBeNull()
            }
        }

        // ========== onAppForegrounded Tests ==========

        test("onAppForegrounded does nothing when backgroundedAtMs is null") {
            runTest {
                // Given
                val userRepository = createMockUserRepository()
                val channel = libraryAdminChannel()
                everySuspend { userRepository.refreshCurrentUser() } returns null
                everySuspend { userRepository.getCurrentUser() } returns null
                val viewModel = AppStartupViewModel(userRepository, channel, createMockAuthSession(), createMockSyncRepository())
                advanceUntilIdle()

                // Verify backgroundedAtMs is null before the call
                viewModel.state.value.backgroundedAtMs shouldBe null
                val isCheckingBefore = viewModel.state.value.isChecking

                // When
                viewModel.onAppForegrounded()
                advanceUntilIdle()

                // Then - state unchanged
                viewModel.state.value.isChecking shouldBe isCheckingBefore
            }
        }

        test("onAppForegrounded does NOT reset isChecking for short background period") {
            runTest {
                // Given
                val userRepository = createMockUserRepository()
                val channel = libraryAdminChannel()
                everySuspend { userRepository.refreshCurrentUser() } returns null
                everySuspend { userRepository.getCurrentUser() } returns null
                val viewModel = AppStartupViewModel(userRepository, channel, createMockAuthSession(), createMockSyncRepository())
                advanceUntilIdle()

                // Initial check is complete, isChecking should be false
                viewModel.state.value.isChecking shouldBe false

                // Simulate backgrounding and immediate foregrounding (short period)
                viewModel.onAppBackgrounded()

                // When - foreground immediately (elapsed time ~0ms, well under 30 min threshold)
                viewModel.onAppForegrounded()
                advanceUntilIdle()

                // Then - isChecking should still be false (no re-check triggered)
                viewModel.state.value.isChecking shouldBe false
            }
        }

        // ========== Setup-status Failure Tests ==========

        test("admin getSetupStatus failure surfaces setupCheckFailed not silent Shell") {
            runTest {
                // Given - admin user, setup-status check fails (e.g. transient network error)
                val userRepository = createMockUserRepository()
                val service = mock<LibraryAdminService>()
                val channel = libraryAdminChannel(service)
                val adminUser = createTestUser(isAdmin = true)
                everySuspend { userRepository.refreshCurrentUser() } returns adminUser
                everySuspend { userRepository.getCurrentUser() } returns adminUser
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Failure(TransportError.NetworkUnavailable())

                // When
                val viewModel = AppStartupViewModel(userRepository, channel, createMockAuthSession(), createMockSyncRepository())
                advanceUntilIdle()

                // Then - check is settled, failure surfaced, NOT forced into the wizard
                viewModel.state.value.isChecking shouldBe false
                viewModel.state.value.setupCheckFailed shouldBe true
                viewModel.state.value.needsLibrarySetup shouldBe false
            }
        }

        test("setup-check failure does NOT swallow CancellationException into setupCheckFailed") {
            runTest {
                // Given - admin user, no local library so the check flows to the server path; setup-status
                // fails, and resolveOfflineOrFail's local-library re-probe is cancelled mid-flight (the
                // coroutine scope is being torn down). The sequential stub makes the first probe (the
                // local-first top check) report "no library" and the second (inside resolveOfflineOrFail)
                // throw — so this exercises that resolveOfflineOrFail suspendRunCatching CE guard directly.
                val userRepository = createMockUserRepository()
                val service = mock<LibraryAdminService>()
                val channel = libraryAdminChannel(service)
                val adminUser = createTestUser(isAdmin = true)
                everySuspend { userRepository.refreshCurrentUser() } returns adminUser
                everySuspend { userRepository.getCurrentUser() } returns adminUser
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Failure(TransportError.NetworkUnavailable())
                val sync = createMockSyncRepository()
                var probed = false
                everySuspend { sync.hasLocalLibrary() } calls {
                    if (!probed) {
                        probed = true
                        false
                    } else {
                        throw CancellationException("scope cancelled")
                    }
                }

                // When
                val viewModel = AppStartupViewModel(userRepository, channel, createMockAuthSession(), sync)
                advanceUntilIdle()

                // Then - cancellation must propagate, not be mistaken for "no local library" and
                // surfaced as the retryable-error wall.
                viewModel.state.value.setupCheckFailed shouldBe false
            }
        }

        test("admin getSetupStatus success needsSetup true sets needsLibrarySetup not failed") {
            runTest {
                // Given
                val userRepository = createMockUserRepository()
                val service = mock<LibraryAdminService>()
                val channel = libraryAdminChannel(service)
                val adminUser = createTestUser(isAdmin = true)
                everySuspend { userRepository.refreshCurrentUser() } returns adminUser
                everySuspend { userRepository.getCurrentUser() } returns adminUser
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = true))

                // When
                val viewModel = AppStartupViewModel(userRepository, channel, createMockAuthSession(), createMockSyncRepository())
                advanceUntilIdle()

                // Then
                viewModel.state.value.isChecking shouldBe false
                viewModel.state.value.needsLibrarySetup shouldBe true
                viewModel.state.value.setupCheckFailed shouldBe false
            }
        }

        test("non-admin is never blocked or failed by setup-status") {
            runTest {
                // Given - non-admin user; getSetupStatus must never be consulted
                val userRepository = createMockUserRepository()
                val channel = libraryAdminChannel()
                val regularUser = createTestUser(isAdmin = false)
                everySuspend { userRepository.refreshCurrentUser() } returns regularUser
                everySuspend { userRepository.getCurrentUser() } returns regularUser

                // When
                val viewModel = AppStartupViewModel(userRepository, channel, createMockAuthSession(), createMockSyncRepository())
                advanceUntilIdle()

                // Then
                viewModel.state.value.isChecking shouldBe false
                viewModel.state.value.needsLibrarySetup shouldBe false
                viewModel.state.value.setupCheckFailed shouldBe false
            }
        }

        test("retryLibrarySetupCheck re-runs the check after a transient failure") {
            runTest {
                // Given - admin user; first check fails, then the next check succeeds
                val userRepository = createMockUserRepository()
                val service = mock<LibraryAdminService>()
                val channel = libraryAdminChannel(service)
                val adminUser = createTestUser(isAdmin = true)
                everySuspend { userRepository.refreshCurrentUser() } returns adminUser
                everySuspend { userRepository.getCurrentUser() } returns adminUser
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Failure(TransportError.NetworkUnavailable())

                val viewModel = AppStartupViewModel(userRepository, channel, createMockAuthSession(), createMockSyncRepository())
                advanceUntilIdle()
                viewModel.state.value.setupCheckFailed shouldBe true

                // Flip the stub to success and retry
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false))

                // When
                viewModel.retryLibrarySetupCheck()
                advanceUntilIdle()

                // Then - failure cleared, check settled, no wizard
                viewModel.state.value.isChecking shouldBe false
                viewModel.state.value.setupCheckFailed shouldBe false
                viewModel.state.value.needsLibrarySetup shouldBe false
            }
        }

        test("onAppForegrounded preserves needsLibrarySetup state for short background period") {
            runTest {
                // Given - admin user with library needing setup
                val userRepository = createMockUserRepository()
                val service = mock<LibraryAdminService>()
                val channel = libraryAdminChannel(service)
                val adminUser = createTestUser(isAdmin = true)
                everySuspend { userRepository.refreshCurrentUser() } returns adminUser
                everySuspend { userRepository.getCurrentUser() } returns adminUser
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = true))

                val viewModel = AppStartupViewModel(userRepository, channel, createMockAuthSession(), createMockSyncRepository())
                advanceUntilIdle()

                // Verify initial state
                viewModel.state.value.isChecking shouldBe false
                viewModel.state.value.needsLibrarySetup shouldBe true

                // Simulate short background period
                viewModel.onAppBackgrounded()

                // When - foreground after short period
                viewModel.onAppForegrounded()
                advanceUntilIdle()

                // Then - state preserved, no re-check
                viewModel.state.value.isChecking shouldBe false
                viewModel.state.value.needsLibrarySetup shouldBe true
            }
        }

        // ========== Offline-first Fallback Tests ==========

        // Regression: a returning admin with a cached local library must NOT be stranded on a
        // "Library Check Failed" wall when the server is unreachable at startup. The setup check
        // only matters for a FRESH admin (no library yet). When books exist locally, the VM must
        // resolve to Ready so the offline library is accessible.
        test("admin getSetupStatus failure resolves to Ready when a local library exists") {
            runTest {
                // Given - admin user, setup-status check fails (server unreachable offline)
                //         but a library is already cached in local Room
                val userRepository = createMockUserRepository()
                val service = mock<LibraryAdminService>()
                val channel = libraryAdminChannel(service)
                val adminUser = createTestUser(isAdmin = true)
                everySuspend { userRepository.refreshCurrentUser() } returns adminUser
                everySuspend { userRepository.getCurrentUser() } returns adminUser
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Failure(TransportError.NetworkUnavailable())
                // hasLocalLibrary = true: returning user with a cached library
                val syncRepository = createMockSyncRepository(hasLocalLibrary = true)

                // When
                val viewModel = AppStartupViewModel(userRepository, channel, createMockAuthSession(), syncRepository)
                advanceUntilIdle()

                // Then - opens offline, NOT stranded on the error wall
                viewModel.state.value.isChecking shouldBe false
                viewModel.state.value.setupCheckFailed shouldBe false
                viewModel.state.value.needsLibrarySetup shouldBe false
                viewModel.readiness.value shouldBe LibraryReadiness.Ready
            }
        }

        // Complement: when there is genuinely no local library (fresh admin, first startup offline),
        // the retryable error wall is the correct and honest response.
        test("admin getSetupStatus failure surfaces CheckFailed when no local library exists") {
            runTest {
                // Given - admin user, setup-status check fails and no local library
                val userRepository = createMockUserRepository()
                val service = mock<LibraryAdminService>()
                val channel = libraryAdminChannel(service)
                val adminUser = createTestUser(isAdmin = true)
                everySuspend { userRepository.refreshCurrentUser() } returns adminUser
                everySuspend { userRepository.getCurrentUser() } returns adminUser
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Failure(TransportError.NetworkUnavailable())
                // hasLocalLibrary = false: fresh admin, no cache yet
                val syncRepository = createMockSyncRepository(hasLocalLibrary = false)

                // When
                val viewModel = AppStartupViewModel(userRepository, channel, createMockAuthSession(), syncRepository)
                advanceUntilIdle()

                // Then - retryable error surfaced (honest over silent for genuine no-library case)
                viewModel.state.value.isChecking shouldBe false
                viewModel.state.value.setupCheckFailed shouldBe true
                viewModel.state.value.needsLibrarySetup shouldBe false
                viewModel.readiness.value shouldBe LibraryReadiness.CheckFailed
            }
        }

        // Regression: when the server is unreachable at cold start, the setup check's RPC calls
        // (refreshCurrentUser / getSetupStatus over the /api/rpc/authed WebSocket) can hang forever —
        // Ktor's HttpTimeout does not bound a post-WebSocket-upgrade RPC stall on Darwin/URLSession.
        // Without an overall timeout the check never resolves, readiness stays Checking, and iOS shows
        // the splash screen indefinitely. The check must bound itself and fall through to the offline
        // resolution so the app is never stranded on the splash.
        test("setup check that hangs against an unreachable server times out and resolves, not an infinite splash") {
            runTest {
                // Given - the current-user RPC never returns (the unreachable-server stall)
                val userRepository = createMockUserRepository()
                val channel = libraryAdminChannel()
                everySuspend { userRepository.refreshCurrentUser() } calls { awaitCancellation() }
                everySuspend { userRepository.getCurrentUser() } returns null
                val syncRepository = createMockSyncRepository(hasLocalLibrary = false)

                // When
                val viewModel = AppStartupViewModel(userRepository, channel, createMockAuthSession(), syncRepository)
                advanceUntilIdle()

                // Then - the check timed out and resolved offline instead of hanging on Checking. With
                // no local library the honest CheckFailed wall shows (→ MainTabView), dismissing splash.
                viewModel.state.value.isChecking shouldBe false
                viewModel.state.value.checkResolved shouldBe true
                viewModel.readiness.value shouldBe LibraryReadiness.CheckFailed
            }
        }

        // Offline-first responsiveness: a returning user already has the library in Room, so the app
        // is Ready the instant we can read local state — the splash must NOT wait on any server
        // round-trip. Server work (user refresh, setup-status, delta sync) is background reconciliation
        // that can only ever upgrade the experience, never gate it.
        test("resolves Ready instantly from a local library without any server round-trip") {
            runTest {
                // Given - admin with a cached local library; the setup-status RPC would hang forever if
                // the local-first path ever consulted it.
                val userRepository = createMockUserRepository()
                val service = mock<LibraryAdminService>()
                val channel = libraryAdminChannel(service)
                val adminUser = createTestUser(isAdmin = true)
                everySuspend { userRepository.refreshCurrentUser() } returns adminUser
                everySuspend { userRepository.getCurrentUser() } returns adminUser
                everySuspend { service.getSetupStatus() } calls { awaitCancellation() }
                val syncRepository = createMockSyncRepository(hasLocalLibrary = true)

                // When - run only the work scheduled at the current instant; do NOT advance virtual time
                // past any timeout.
                val viewModel = AppStartupViewModel(userRepository, channel, createMockAuthSession(), syncRepository)
                runCurrent()

                // Then - Ready is resolved synchronously from local content, before (and without) the
                // hanging server call. Pre-change the check would call getSetupStatus, hang, and stay
                // Checking here — only reaching Ready after the full timeout.
                viewModel.state.value.checkResolved shouldBe true
                viewModel.state.value.isChecking shouldBe false
                viewModel.state.value.needsLibrarySetup shouldBe false
                viewModel.readiness.value shouldBe LibraryReadiness.Ready
            }
        }
    })
