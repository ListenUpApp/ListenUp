@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.calypsan.listenup.client.presentation.startup

import app.cash.turbine.test
import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.dto.SetupStatus
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.LibraryAdminRpcFactory
import com.calypsan.listenup.client.domain.model.AuthState
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.domain.model.User
import com.calypsan.listenup.client.domain.repository.AuthSession
import com.calypsan.listenup.client.domain.repository.ProfileRepository
import com.calypsan.listenup.client.domain.repository.SyncRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.api.result.AppResult as CoreAppResult
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Behavioural spec for [AppStartupViewModel.readiness] — the single authoritative onboarding state
 * the navigation layer consumes via one `when`. Drives the VM through the three onboarding journeys
 * and asserts the exact [LibraryReadiness] transition sequence via Turbine.
 *
 * The load-bearing case is `fresh admin`: `Ready` must arrive only after the populating signal
 * clears — proving the gate spans the client's import, not just the server's scan.
 */
class LibraryReadinessTest :
    FunSpec({
        val dispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(dispatcher) }
        afterTest { Dispatchers.resetMain() }

        fun adminUser() =
            User(
                id = UserId("user-001"),
                email = "admin@example.com",
                displayName = "Admin",
                firstName = null,
                lastName = null,
                isAdmin = true,
                avatarType = "auto",
                avatarValue = null,
                avatarColor = "#3B82F6",
                tagline = null,
                createdAtMs = 0L,
                updatedAtMs = 0L,
            )

        fun userRepoReturning(user: User): UserRepository {
            val repo = mock<UserRepository>()
            everySuspend { repo.refreshCurrentUser() } returns user
            everySuspend { repo.getCurrentUser() } returns user
            return repo
        }

        fun adminFactory(service: LibraryAdminService): LibraryAdminRpcFactory {
            val factory = mock<LibraryAdminRpcFactory>()
            everySuspend { factory.get() } returns service
            return factory
        }

        fun authSession(state: MutableStateFlow<AuthState>): AuthSession {
            val session = mock<AuthSession>()
            every { session.authState } returns state
            return session
        }

        // Profile refresh is fire-and-forget at startup and not exercised here — a no-op stub.
        fun profileRepo(): ProfileRepository {
            val repo = mock<ProfileRepository>()
            everySuspend { repo.refreshMyProfile() } returns CoreAppResult.Success(Unit)
            return repo
        }

        fun syncRepo(
            scanning: MutableStateFlow<Boolean>,
            progress: MutableStateFlow<ScanProgressState?> = MutableStateFlow(null),
        ): SyncRepository {
            val sync = mock<SyncRepository>()
            every { sync.isBuildingInitialLibrary } returns scanning
            every { sync.scanProgress } returns progress
            return sync
        }

        fun progressState(current: Int = 0) =
            ScanProgressState(
                phase = "analyzing",
                current = current,
                total = current,
                added = 0,
                updated = 0,
                removed = 0,
            )

        // Admin whose library is already set up; the gate is then driven purely by the scan signal.
        fun setUpAdminService(): LibraryAdminService {
            val service = mock<LibraryAdminService>()
            everySuspend { service.getSetupStatus() } returns
                AppResult.Success(SetupStatus(needsSetup = false))
            return service
        }

        val authenticated = AuthState.Authenticated(UserId("user-001"), SessionId("session-001"))

        test("fresh admin: Checking -> NeedsSetup -> Populating -> Ready") {
            runTest(dispatcher) {
                val service = mock<LibraryAdminService>()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = true))
                val scanning = MutableStateFlow(false)
                // Start unauthenticated so the setup check fires on the transition, as in real launch.
                val authState = MutableStateFlow<AuthState>(AuthState.NeedsLogin(openRegistration = false))

                val vm =
                    AppStartupViewModel(
                        userRepoReturning(adminUser()),
                        adminFactory(service),
                        authSession(authState),
                        profileRepo(),
                        syncRepo(scanning),
                    )

                vm.readiness.test {
                    awaitItem() shouldBe LibraryReadiness.Checking

                    authState.value = authenticated
                    advanceUntilIdle()
                    awaitItem() shouldBe LibraryReadiness.NeedsSetup

                    // Wizard done + the initial scan begins: needs-setup clears while scanning is up.
                    // runCurrent (not advanceUntilIdle) so virtual time doesn't leap past the
                    // populating stall watchdog and spuriously mark the gate stalled.
                    scanning.value = true
                    vm.onLibrarySetupComplete()
                    runCurrent()
                    awaitItem() shouldBe LibraryReadiness.Populating(null)

                    // Server scan + client import settle → shell is safe to mount.
                    scanning.value = false
                    runCurrent()
                    awaitItem() shouldBe LibraryReadiness.Ready
                }
            }
        }

        test("relaunch with an existing library and no scan: Checking -> Ready (no spurious Populating)") {
            runTest(dispatcher) {
                val service = mock<LibraryAdminService>()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Success(SetupStatus(needsSetup = false))

                val vm =
                    AppStartupViewModel(
                        userRepoReturning(adminUser()),
                        adminFactory(service),
                        authSession(MutableStateFlow(authenticated)),
                        profileRepo(),
                        syncRepo(MutableStateFlow(false)),
                    )

                vm.readiness.test {
                    awaitItem() shouldBe LibraryReadiness.Checking
                    advanceUntilIdle()
                    awaitItem() shouldBe LibraryReadiness.Ready
                }
            }
        }

        test("setup-status failure: Checking -> CheckFailed") {
            runTest(dispatcher) {
                val service = mock<LibraryAdminService>()
                everySuspend { service.getSetupStatus() } returns
                    AppResult.Failure(TransportError.NetworkUnavailable())

                val vm =
                    AppStartupViewModel(
                        userRepoReturning(adminUser()),
                        adminFactory(service),
                        authSession(MutableStateFlow(authenticated)),
                        profileRepo(),
                        syncRepo(MutableStateFlow(false)),
                    )

                vm.readiness.test {
                    awaitItem() shouldBe LibraryReadiness.Checking
                    advanceUntilIdle()
                    awaitItem() shouldBe LibraryReadiness.CheckFailed
                }
            }
        }

        // ===================== Never-stranded stall escape =====================

        // A scan that goes quiet for the whole timeout must flip the gate to `stalled` so the
        // populating screen can offer the "Continue" escape. State is read off the Eagerly-shared
        // StateFlow with explicit clock control, so the not-yet/just-crossed boundary is exact.
        test("populating gate stalls once scan progress is quiet for the timeout") {
            runTest(dispatcher) {
                val scanning = MutableStateFlow(true)
                val vm =
                    AppStartupViewModel(
                        userRepoReturning(adminUser()),
                        adminFactory(setUpAdminService()),
                        authSession(MutableStateFlow(authenticated)),
                        profileRepo(),
                        syncRepo(scanning),
                    )

                runCurrent()
                vm.readiness.value shouldBe LibraryReadiness.Populating(null)

                // Just shy of the timeout: still not stalled.
                advanceTimeBy(AppStartupViewModel.POPULATING_STALL_TIMEOUT_MS - 1)
                runCurrent()
                vm.readiness.value shouldBe LibraryReadiness.Populating(null, stalled = false)

                // Crossing the timeout latches stalled.
                advanceTimeBy(1)
                runCurrent()
                vm.readiness.value shouldBe LibraryReadiness.Populating(null, stalled = true)
            }
        }

        // A healthy-but-slow scan keeps advancing progress; each advance must reset the watchdog, so
        // the gate never trips even though far more than one timeout's worth of time elapses overall.
        test("advancing scan progress keeps resetting the stall watchdog") {
            runTest(dispatcher) {
                val scanning = MutableStateFlow(true)
                val progress = MutableStateFlow<ScanProgressState?>(progressState(current = 0))
                val vm =
                    AppStartupViewModel(
                        userRepoReturning(adminUser()),
                        adminFactory(setUpAdminService()),
                        authSession(MutableStateFlow(authenticated)),
                        profileRepo(),
                        syncRepo(scanning, progress),
                    )

                runCurrent()
                vm.readiness.value shouldBe LibraryReadiness.Populating(progressState(0))

                repeat(4) { tick ->
                    // Nearly a full timeout passes, then progress advances — resetting the timer.
                    advanceTimeBy(AppStartupViewModel.POPULATING_STALL_TIMEOUT_MS - 1_000)
                    runCurrent()
                    vm.readiness.value
                        .shouldBeInstanceOf<LibraryReadiness.Populating>()
                        .stalled shouldBe false
                    progress.value = progressState(current = tick + 1)
                    runCurrent()
                }

                vm.readiness.value
                    .shouldBeInstanceOf<LibraryReadiness.Populating>()
                    .stalled shouldBe false
            }
        }

        // Tapping Continue latches readiness to Ready even though the server scan signal is still up,
        // and the latch holds — the populating gate never re-shows for the rest of the session.
        test("Continue latches readiness to Ready and the latch holds while the scan keeps running") {
            runTest(dispatcher) {
                val scanning = MutableStateFlow(true)
                val vm =
                    AppStartupViewModel(
                        userRepoReturning(adminUser()),
                        adminFactory(setUpAdminService()),
                        authSession(MutableStateFlow(authenticated)),
                        profileRepo(),
                        syncRepo(scanning),
                    )

                runCurrent()
                advanceTimeBy(AppStartupViewModel.POPULATING_STALL_TIMEOUT_MS)
                runCurrent()
                vm.readiness.value shouldBe LibraryReadiness.Populating(null, stalled = true)

                // Continue → Ready, despite the scan still running underneath.
                vm.onContinueToPartialLibrary()
                runCurrent()
                vm.readiness.value shouldBe LibraryReadiness.Ready
                scanning.value shouldBe true

                // Latch holds: more time passes, scan still up — never re-shows the gate.
                advanceTimeBy(AppStartupViewModel.POPULATING_STALL_TIMEOUT_MS * 2)
                runCurrent()
                vm.readiness.value shouldBe LibraryReadiness.Ready
            }
        }
    })
