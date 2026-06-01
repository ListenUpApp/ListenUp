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
import com.calypsan.listenup.api.dto.invite.InvitePreview
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.InviteRepository
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

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
            val vm = ClaimInviteViewModel(repo)

            vm.state.value.shouldBeInstanceOf<ClaimInviteUiState.Idle>()
        }

        test("onCodeEntered transitions Idle to LookingUp to Preview on success") {
            runTest(testDispatcher) {
                val repo = mock<InviteRepository>()
                everySuspend { repo.lookupInvite(any()) } returns AppResult.Success(fakePreview())
                val vm = ClaimInviteViewModel(repo)

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
                val vm = ClaimInviteViewModel(repo)

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
                val vm = ClaimInviteViewModel(repo)

                vm.onCodeEntered(INVITE_CODE)
                testDispatcher.scheduler.advanceUntilIdle()

                vm.state.test {
                    awaitItem().shouldBeInstanceOf<ClaimInviteUiState.Preview>()
                    vm.onClaimSubmit("password123", "Alice")
                    awaitItem().shouldBeInstanceOf<ClaimInviteUiState.Submitting>()
                    awaitItem().shouldBeInstanceOf<ClaimInviteUiState.Claimed>()
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }

        test("onClaimSubmit transitions to Error on claim failure") {
            runTest(testDispatcher) {
                val repo = mock<InviteRepository>()
                everySuspend { repo.lookupInvite(any()) } returns AppResult.Success(fakePreview())
                everySuspend { repo.claimInvite(any(), any(), any()) } returns
                    AppResult.Failure(AuthError.InvalidCredentials())
                val vm = ClaimInviteViewModel(repo)

                vm.onCodeEntered(INVITE_CODE)
                testDispatcher.scheduler.advanceUntilIdle()
                vm.onClaimSubmit("password123", null)
                testDispatcher.scheduler.advanceUntilIdle()

                val error = vm.state.value.shouldBeInstanceOf<ClaimInviteUiState.Error>()
                error.message shouldBe AuthError.InvalidCredentials().message
            }
        }

        test("onClaimSubmit before a code is known is a no-op") {
            runTest(testDispatcher) {
                val repo = mock<InviteRepository>()
                val vm = ClaimInviteViewModel(repo)

                vm.onClaimSubmit("password123", null)
                testDispatcher.scheduler.advanceUntilIdle()

                vm.state.value.shouldBeInstanceOf<ClaimInviteUiState.Idle>()
            }
        }
    })
