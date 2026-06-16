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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
            every { sync.isServerScanning } returns scanning
            every { sync.scanProgress } returns progress
            return sync
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
                    scanning.value = true
                    vm.onLibrarySetupComplete()
                    advanceUntilIdle()
                    awaitItem() shouldBe LibraryReadiness.Populating(null)

                    // Server scan + client import settle → shell is safe to mount.
                    scanning.value = false
                    advanceUntilIdle()
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
    })
