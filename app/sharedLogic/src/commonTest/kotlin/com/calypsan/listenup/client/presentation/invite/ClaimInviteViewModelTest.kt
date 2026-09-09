package com.calypsan.listenup.client.presentation.invite

import app.cash.turbine.test
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.dto.ServerInfo
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.invite.InvitePreview
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.InstanceRepository
import com.calypsan.listenup.client.domain.repository.InviteRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import com.calypsan.listenup.client.domain.repository.VerifiedServer
import com.calypsan.listenup.core.ServerUrl
import dev.mokkery.answering.calls
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.calypsan.listenup.client.domain.repository.AuthSession as DomainAuthSession

private const val INVITE_CODE = "ABC123"

private fun fakePreview(): InvitePreview =
    InvitePreview(
        displayName = "Alice Anderson",
        email = "alice@example.com",
        invitedByName = "Bob",
        serverName = "Test Server",
        valid = true,
    )

private fun fakeSession(): AuthSession =
    AuthSession(
        accessToken = AccessToken("access-token"),
        accessTokenExpiresAt = 1_000L,
        refreshToken = RefreshToken("refresh-token"),
        refreshTokenExpiresAt = 2_000L,
        sessionId = SessionId("session-1"),
        user =
            User(
                id = UserId("user-1"),
                email = "alice@example.com",
                displayName = "Alice Anderson",
                role = UserRole.MEMBER,
                status = UserStatus.ACTIVE,
                createdAt = 0L,
            ),
    )

/**
 * Tests for [ClaimInviteViewModel] — the lookup → preview → claim flow folding
 * the typed [AppResult] over [ClaimInviteUiState] transitions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClaimInviteViewModelTest :
    FunSpec({

        val testDispatcher = StandardTestDispatcher()

        beforeTest { Dispatchers.setMain(testDispatcher) }
        afterTest { Dispatchers.resetMain() }

        test("initial state is Idle") {
            val repo = mock<InviteRepository>()
            val vm = ClaimInviteViewModel(repo, mock(), mock(), mock())

            vm.state.value.shouldBeInstanceOf<ClaimInviteUiState.Idle>()
        }

        test("onCodeEntered transitions Idle to LookingUp to Preview on success") {
            runTest(testDispatcher) {
                val repo = mock<InviteRepository>()
                everySuspend { repo.lookupInvite(any()) } returns AppResult.Success(fakePreview())
                val vm = ClaimInviteViewModel(repo, mock(), mock(), mock())

                vm.state.test {
                    awaitItem().shouldBeInstanceOf<ClaimInviteUiState.Idle>()
                    vm.onCodeEntered(INVITE_CODE)
                    awaitItem().shouldBeInstanceOf<ClaimInviteUiState.LookingUp>()
                    val preview = awaitItem().shouldBeInstanceOf<ClaimInviteUiState.Preview>()
                    preview.preview shouldBe fakePreview()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("onCodeEntered transitions to Error on lookup failure") {
            runTest(testDispatcher) {
                val repo = mock<InviteRepository>()
                everySuspend { repo.lookupInvite(any()) } returns
                    AppResult.Failure(InternalError(correlationId = "corr-1"))
                val vm = ClaimInviteViewModel(repo, mock(), mock(), mock())

                vm.onCodeEntered(INVITE_CODE)
                testDispatcher.scheduler.advanceUntilIdle()

                val error = vm.state.value.shouldBeInstanceOf<ClaimInviteUiState.Error>()
                error.message shouldBe InternalError(correlationId = "corr-1").message
            }
        }

        test("onClaimSubmit transitions to Submitting then Claimed on success") {
            runTest(testDispatcher) {
                val repo = mock<InviteRepository>()
                everySuspend { repo.lookupInvite(any()) } returns AppResult.Success(fakePreview())
                everySuspend { repo.claimInvite(any(), any(), any()) } returns
                    AppResult.Success(fakeSession())
                val vm = ClaimInviteViewModel(repo, mock(), mock(), mock())

                vm.onCodeEntered(INVITE_CODE)
                testDispatcher.scheduler.advanceUntilIdle()

                vm.state.test {
                    awaitItem().shouldBeInstanceOf<ClaimInviteUiState.Preview>()
                    vm.onClaimSubmit("password123", "Alice", "Anderson")
                    awaitItem().shouldBeInstanceOf<ClaimInviteUiState.Submitting>()
                    awaitItem().shouldBeInstanceOf<ClaimInviteUiState.Claimed>()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("onClaimSubmit joins the first and last name into a single display name") {
            runTest(testDispatcher) {
                val repo = mock<InviteRepository>()
                everySuspend { repo.lookupInvite(any()) } returns AppResult.Success(fakePreview())
                everySuspend { repo.claimInvite(any(), any(), any()) } returns
                    AppResult.Success(fakeSession())
                val vm = ClaimInviteViewModel(repo, mock(), mock(), mock())

                vm.onCodeEntered(INVITE_CODE)
                testDispatcher.scheduler.advanceUntilIdle()
                vm.onClaimSubmit("password123", " Alice ", " Anderson ")
                testDispatcher.scheduler.advanceUntilIdle()

                verifySuspend { repo.claimInvite(INVITE_CODE, "password123", "Alice Anderson") }
            }
        }

        test("onClaimSubmit transitions to Error on claim failure") {
            runTest(testDispatcher) {
                val repo = mock<InviteRepository>()
                everySuspend { repo.lookupInvite(any()) } returns AppResult.Success(fakePreview())
                everySuspend { repo.claimInvite(any(), any(), any()) } returns
                    AppResult.Failure(AuthError.InvalidCredentials())
                val vm = ClaimInviteViewModel(repo, mock(), mock(), mock())

                vm.onCodeEntered(INVITE_CODE)
                testDispatcher.scheduler.advanceUntilIdle()
                vm.onClaimSubmit("password123", "", "")
                testDispatcher.scheduler.advanceUntilIdle()

                val error = vm.state.value.shouldBeInstanceOf<ClaimInviteUiState.Error>()
                error.message shouldBe AuthError.InvalidCredentials().message
            }
        }

        test("onClaimSubmit before a code is known is a no-op") {
            runTest(testDispatcher) {
                val repo = mock<InviteRepository>()
                val vm = ClaimInviteViewModel(repo, mock(), mock(), mock())

                vm.onClaimSubmit("password123", "", "")
                testDispatcher.scheduler.advanceUntilIdle()

                vm.state.value.shouldBeInstanceOf<ClaimInviteUiState.Idle>()
            }
        }

        // Deep-link claim race guard: the server URL the link carries MUST be
        // persisted (and applied to ServerConfig) before the invite lookup runs,
        // otherwise the RPC factory can't resolve a base URL on a fresh install
        // and the claim lands on Error instead of Preview. Both calls record into
        // a shared sequence so the assertion fails if the order is ever reversed.
        // The device is already pointed at the link's host here, so no confirmation
        // step sits between the two — the order is the whole point of this test.
        test("start persists the server URL before looking up the invite") {
            runTest(testDispatcher) {
                val events = mutableListOf<String>()

                val serverConfig =
                    mock<ServerConfig> {
                        everySuspend { getServerUrl() } returns ServerUrl("https://example.com")
                        everySuspend { setServerUrl(any()) } calls { events.add("setServerUrl") }
                    }
                val repo =
                    mock<InviteRepository> {
                        everySuspend { lookupInvite(any()) } calls
                            {
                                events.add("lookupInvite")
                                AppResult.Success(fakePreview())
                            }
                    }
                val instanceRepository =
                    mock<InstanceRepository> {
                        everySuspend { findReachableUrl(any()) } returns "https://example.com"
                        everySuspend { verifyServer(any()) } returns AppResult.Failure(InternalError())
                    }
                val vm = ClaimInviteViewModel(repo, serverConfig, instanceRepository, mock())

                vm.start(serverUrl = "https://example.com", code = INVITE_CODE)
                testDispatcher.scheduler.advanceUntilIdle()

                events shouldContainInOrder listOf("setServerUrl", "lookupInvite")
                vm.state.value.shouldBeInstanceOf<ClaimInviteUiState.Preview>()
            }
        }

        // Manual (non-deeplink) entry presumes ServerConfig is already set, so a
        // null server URL must skip setServerUrl entirely and go straight to lookup.
        test("start with a null server URL skips setServerUrl and looks up directly") {
            runTest(testDispatcher) {
                val events = mutableListOf<String>()

                val serverConfig =
                    mock<ServerConfig> {
                        everySuspend { setServerUrl(any()) } calls { events.add("setServerUrl") }
                    }
                val repo =
                    mock<InviteRepository> {
                        everySuspend { lookupInvite(any()) } calls
                            {
                                events.add("lookupInvite")
                                AppResult.Success(fakePreview())
                            }
                    }
                val vm = ClaimInviteViewModel(repo, serverConfig, mock(), mock())

                vm.start(serverUrl = null, code = INVITE_CODE)
                testDispatcher.scheduler.advanceUntilIdle()

                events shouldBe listOf("lookupInvite")
                vm.state.value.shouldBeInstanceOf<ClaimInviteUiState.Preview>()
            }
        }

        // Off-LAN invitee: the link's local URL is unreachable, so the reachability probe picks the
        // remote (WAN) URL and that is what gets persisted before lookup. Local is offered first.
        // The device is already pointed at the remote host, so the confirmation step is bypassed
        // and the test stays about which URL the probe selects.
        test("start persists the reachable URL from the link, falling back local → remote") {
            runTest(testDispatcher) {
                val serverConfig =
                    mock<ServerConfig> {
                        everySuspend { getServerUrl() } returns ServerUrl("https://remote.example.com")
                        everySuspend { setServerUrl(any()) } returns Unit
                    }
                val instanceRepository =
                    mock<InstanceRepository> {
                        everySuspend { findReachableUrl(any()) } returns "https://remote.example.com"
                        everySuspend { verifyServer(any()) } returns AppResult.Failure(InternalError())
                    }
                val repo =
                    mock<InviteRepository> {
                        everySuspend { lookupInvite(any()) } returns AppResult.Success(fakePreview())
                    }
                val vm = ClaimInviteViewModel(repo, serverConfig, instanceRepository, mock())

                vm.start(
                    serverUrl = "http://192.168.1.5:8080",
                    code = INVITE_CODE,
                    remoteUrl = "https://remote.example.com",
                )
                testDispatcher.scheduler.advanceUntilIdle()

                verifySuspend {
                    instanceRepository.findReachableUrl(listOf("http://192.168.1.5:8080", "https://remote.example.com"))
                }
                verifySuspend { serverConfig.setServerUrl(ServerUrl("https://remote.example.com")) }
                vm.state.value.shouldBeInstanceOf<ClaimInviteUiState.Preview>()
            }
        }

        // Already pointed at the link's host: no move is happening, so there is nothing to confirm and
        // the flow goes straight through — persist, then look up, never a confirmation step.
        test("start proceeds without confirmation when the device is already pointed at the link's host") {
            runTest(testDispatcher) {
                val events = mutableListOf<String>()

                val serverConfig =
                    mock<ServerConfig> {
                        everySuspend { getServerUrl() } returns ServerUrl("https://example.com")
                        everySuspend { setServerUrl(any()) } calls { events.add("setServerUrl") }
                    }
                val repo =
                    mock<InviteRepository> {
                        everySuspend { lookupInvite(any()) } calls
                            {
                                events.add("lookupInvite")
                                AppResult.Success(fakePreview())
                            }
                    }
                val instanceRepository =
                    mock<InstanceRepository> {
                        everySuspend { findReachableUrl(any()) } returns "https://example.com"
                        everySuspend { verifyServer(any()) } returns AppResult.Failure(InternalError())
                    }
                val vm = ClaimInviteViewModel(repo, serverConfig, instanceRepository, mock())

                vm.start(serverUrl = "https://example.com", code = INVITE_CODE)
                testDispatcher.scheduler.advanceUntilIdle()

                events shouldBe listOf("setServerUrl", "lookupInvite")
                vm.state.value.shouldBeInstanceOf<ClaimInviteUiState.Preview>()
            }
        }

        // The link is untrusted input and its server address becomes the host every authenticated
        // request is sent to. A device signed in to a DIFFERENT server must see that address and
        // agree before anything is persisted — nothing moves, nothing is looked up, until it does.
        test("start parks on ConfirmServer and persists nothing when signed in to a different host") {
            runTest(testDispatcher) {
                val serverConfig =
                    mock<ServerConfig> {
                        everySuspend { getServerUrl() } returns ServerUrl("https://example.com")
                        everySuspend { setServerUrl(any()) } returns Unit
                    }
                val authSession =
                    mock<DomainAuthSession> {
                        everySuspend { isAuthenticated() } returns true
                    }
                val instanceRepository =
                    mock<InstanceRepository> {
                        everySuspend { findReachableUrl(any()) } returns "https://other.example.com"
                    }
                val repo =
                    mock<InviteRepository> {
                        everySuspend { lookupInvite(any()) } returns AppResult.Success(fakePreview())
                    }
                val vm = ClaimInviteViewModel(repo, serverConfig, instanceRepository, authSession)

                vm.start(serverUrl = "https://other.example.com", code = INVITE_CODE)
                testDispatcher.scheduler.advanceUntilIdle()

                vm.state.value shouldBe
                    ClaimInviteUiState.ConfirmServer(host = "other.example.com", signedInElsewhere = true)
                verifySuspend(VerifyMode.not) { serverConfig.setServerUrl(any()) }
                verifySuspend(VerifyMode.not) { repo.lookupInvite(any()) }
            }
        }

        // The confirmation lands BEFORE the persist, not instead of it: once the user agrees, the
        // set still runs before the lookup on one coroutine (the fresh-install ordering guard).
        test("onConfirmServer persists the link's server and then looks up the invite") {
            runTest(testDispatcher) {
                val events = mutableListOf<String>()

                val serverConfig =
                    mock<ServerConfig> {
                        everySuspend { getServerUrl() } returns ServerUrl("https://example.com")
                        everySuspend { setServerUrl(any()) } calls { events.add("setServerUrl") }
                    }
                val authSession =
                    mock<DomainAuthSession> {
                        everySuspend { isAuthenticated() } returns true
                    }
                val instanceRepository =
                    mock<InstanceRepository> {
                        everySuspend { findReachableUrl(any()) } returns "https://other.example.com"
                        everySuspend { verifyServer(any()) } returns AppResult.Failure(InternalError())
                    }
                val repo =
                    mock<InviteRepository> {
                        everySuspend { lookupInvite(any()) } calls
                            {
                                events.add("lookupInvite")
                                AppResult.Success(fakePreview())
                            }
                    }
                val vm = ClaimInviteViewModel(repo, serverConfig, instanceRepository, authSession)

                vm.start(serverUrl = "https://other.example.com", code = INVITE_CODE)
                testDispatcher.scheduler.advanceUntilIdle()
                vm.state.value.shouldBeInstanceOf<ClaimInviteUiState.ConfirmServer>()

                vm.onConfirmServer()
                testDispatcher.scheduler.advanceUntilIdle()

                events shouldBe listOf("setServerUrl", "lookupInvite")
                verifySuspend { serverConfig.setServerUrl(ServerUrl("https://other.example.com")) }
                vm.state.value.shouldBeInstanceOf<ClaimInviteUiState.Preview>()
            }
        }

        // A fresh install has no stored server and no session, but the address in the link is still
        // untrusted input the user has not seen — so it is shown and confirmed before it is persisted.
        test("start on a fresh install shows the confirmation and proceeds once confirmed") {
            runTest(testDispatcher) {
                val events = mutableListOf<String>()

                val serverConfig =
                    mock<ServerConfig> {
                        everySuspend { getServerUrl() } returns null
                        everySuspend { setServerUrl(any()) } calls { events.add("setServerUrl") }
                    }
                val authSession =
                    mock<DomainAuthSession> {
                        everySuspend { isAuthenticated() } returns false
                    }
                val instanceRepository =
                    mock<InstanceRepository> {
                        everySuspend { findReachableUrl(any()) } returns "https://example.com"
                        everySuspend { verifyServer(any()) } returns AppResult.Failure(InternalError())
                    }
                val repo =
                    mock<InviteRepository> {
                        everySuspend { lookupInvite(any()) } calls
                            {
                                events.add("lookupInvite")
                                AppResult.Success(fakePreview())
                            }
                    }
                val vm = ClaimInviteViewModel(repo, serverConfig, instanceRepository, authSession)

                vm.start(serverUrl = "https://example.com", code = INVITE_CODE)
                testDispatcher.scheduler.advanceUntilIdle()

                vm.state.value shouldBe
                    ClaimInviteUiState.ConfirmServer(host = "example.com", signedInElsewhere = false)
                events shouldBe emptyList()

                vm.onConfirmServer()
                testDispatcher.scheduler.advanceUntilIdle()

                events shouldBe listOf("setServerUrl", "lookupInvite")
                vm.state.value.shouldBeInstanceOf<ClaimInviteUiState.Preview>()
            }
        }

        // Declining the link's server is not a dead end: the flow falls back to manual code entry
        // against whatever server the device already uses, and nothing was persisted on the way.
        test("onCancelServer returns to Idle without persisting the link's server") {
            runTest(testDispatcher) {
                val serverConfig =
                    mock<ServerConfig> {
                        everySuspend { getServerUrl() } returns ServerUrl("https://example.com")
                        everySuspend { setServerUrl(any()) } returns Unit
                    }
                val authSession =
                    mock<DomainAuthSession> {
                        everySuspend { isAuthenticated() } returns true
                    }
                val instanceRepository =
                    mock<InstanceRepository> {
                        everySuspend { findReachableUrl(any()) } returns "https://other.example.com"
                    }
                val repo = mock<InviteRepository>()
                val vm = ClaimInviteViewModel(repo, serverConfig, instanceRepository, authSession)

                vm.start(serverUrl = "https://other.example.com", code = INVITE_CODE)
                testDispatcher.scheduler.advanceUntilIdle()
                vm.state.value.shouldBeInstanceOf<ClaimInviteUiState.ConfirmServer>()

                vm.onCancelServer()
                testDispatcher.scheduler.advanceUntilIdle()

                vm.state.value.shouldBeInstanceOf<ClaimInviteUiState.Idle>()
                verifySuspend(VerifyMode.not) { serverConfig.setServerUrl(any()) }
            }
        }

        // Arm IP-follow for invite-claimed servers: after persisting the reachable URL, the VM
        // captures the server's stable instance id (via a best-effort verify) so ConnectionCoordinator
        // can relocate the server on a later LAN address change — the same as the mDNS-picker path.
        test("start persists the server's instance id for IP-follow on a successful verify") {
            runTest(testDispatcher) {
                val serverConfig =
                    mock<ServerConfig> {
                        everySuspend { getServerUrl() } returns ServerUrl("https://example.com")
                        everySuspend { setServerUrl(any()) } returns Unit
                        everySuspend { setConnectedServerId(any()) } returns Unit
                    }
                val verified =
                    VerifiedServer(
                        serverInfo =
                            ServerInfo(
                                name = "ListenUp",
                                version = "0.0.1",
                                apiVersion = "v1",
                                setupRequired = false,
                                registrationPolicy = RegistrationPolicy.OPEN,
                                instanceId = "inst-xyz",
                            ),
                        verifiedUrl = "https://example.com",
                    )
                val instanceRepository =
                    mock<InstanceRepository> {
                        everySuspend { findReachableUrl(any()) } returns "https://example.com"
                        everySuspend { verifyServer(any()) } returns AppResult.Success(verified)
                    }
                val repo =
                    mock<InviteRepository> {
                        everySuspend { lookupInvite(any()) } returns AppResult.Success(fakePreview())
                    }
                val vm = ClaimInviteViewModel(repo, serverConfig, instanceRepository, mock())

                vm.start(serverUrl = "https://example.com", code = INVITE_CODE)
                testDispatcher.scheduler.advanceUntilIdle()

                verifySuspend { serverConfig.setConnectedServerId("inst-xyz") }
                vm.state.value.shouldBeInstanceOf<ClaimInviteUiState.Preview>()
            }
        }
    })
