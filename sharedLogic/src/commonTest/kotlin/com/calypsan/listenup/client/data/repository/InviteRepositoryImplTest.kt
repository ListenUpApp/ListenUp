package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.api.InviteServicePublic
import com.calypsan.listenup.api.dto.auth.AccessToken
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.DeviceInfo
import com.calypsan.listenup.api.dto.auth.RefreshToken
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.User
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.auth.UserStatus
import com.calypsan.listenup.api.dto.invite.InvitePreview
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.InviteRpcFactory
import com.calypsan.listenup.client.domain.repository.AuthSession as AuthSessionStore
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode.Companion.exactly
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

private const val INVITE_CODE = "ABC123"

private fun fakePreview(valid: Boolean = true): InvitePreview =
    InvitePreview(
        displayName = "Alice Anderson",
        email = "alice@example.com",
        invitedByName = "Bob",
        serverName = "Test Server",
        valid = valid,
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
 * Unit tests for [InviteRepositoryImpl] — the contract-typed RPC adapter.
 *
 * Verifies that lookup/claim dispatch through the [InviteServicePublic] proxy
 * (resolved via a mocked [InviteRpcFactory]) and that a successful claim lands
 * the user logged-in by calling [AuthSessionStore.saveAuthTokens] with the
 * issued session's tokens — while a failed claim does NOT touch the store.
 */
class InviteRepositoryImplTest :
    FunSpec({

        fun repo(
            rpc: InviteRpcFactory = mock(),
            authSession: AuthSessionStore = mock(),
            deviceInfo: DeviceInfo = DeviceInfo(),
        ) = InviteRepositoryImpl(
            rpc = rpc,
            authSession = authSession,
            deviceInfoProvider = { deviceInfo },
        )

        test("lookupInvite dispatches through the public service") {
            runTest {
                val service = mock<InviteServicePublic>()
                everySuspend { service.lookupInvite(INVITE_CODE) } returns AppResult.Success(fakePreview())
                val rpc = mock<InviteRpcFactory>()
                everySuspend { rpc.publicService() } returns service

                val result = repo(rpc = rpc).lookupInvite(INVITE_CODE)

                val preview = result.shouldBeInstanceOf<AppResult.Success<InvitePreview>>()
                preview.data.email shouldBe "alice@example.com"
                verifySuspend { service.lookupInvite(INVITE_CODE) }
            }
        }

        test("claimInvite success lands logged-in via saveAuthTokens") {
            runTest {
                val service = mock<InviteServicePublic>()
                everySuspend { service.claimInvite(INVITE_CODE, "password123", null, DeviceInfo()) } returns
                    AppResult.Success(fakeSession())
                val rpc = mock<InviteRpcFactory>()
                everySuspend { rpc.publicService() } returns service
                val store = mock<AuthSessionStore>()
                everySuspend { store.saveAuthTokens(any(), any(), any(), any()) } returns Unit

                val result = repo(rpc = rpc, authSession = store).claimInvite(INVITE_CODE, "password123", null)

                result.shouldBeInstanceOf<AppResult.Success<AuthSession>>()
                verifySuspend(exactly(1)) {
                    store.saveAuthTokens(
                        AccessToken("access-token"),
                        RefreshToken("refresh-token"),
                        "session-1",
                        "user-1",
                    )
                }
            }
        }

        test("claimInvite sends DeviceInfo from the provider") {
            runTest {
                val service = mock<InviteServicePublic>()
                everySuspend { service.claimInvite(any(), any(), any(), any()) } returns
                    AppResult.Success(fakeSession())
                val rpc = mock<InviteRpcFactory>()
                everySuspend { rpc.publicService() } returns service
                val store = mock<AuthSessionStore>()
                everySuspend { store.saveAuthTokens(any(), any(), any(), any()) } returns Unit

                repo(
                    rpc = rpc,
                    authSession = store,
                    deviceInfo = DeviceInfo(deviceModel = "Pixel 10"),
                ).claimInvite(INVITE_CODE, "password123", null)

                verifySuspend {
                    service.claimInvite(INVITE_CODE, "password123", null, DeviceInfo(deviceModel = "Pixel 10"))
                }
            }
        }

        test("claimInvite failure does NOT call saveAuthTokens") {
            runTest {
                val service = mock<InviteServicePublic>()
                everySuspend { service.claimInvite(any(), any(), any(), any()) } returns
                    AppResult.Failure(AuthError.InvalidCredentials())
                val rpc = mock<InviteRpcFactory>()
                everySuspend { rpc.publicService() } returns service
                val store = mock<AuthSessionStore>()

                val result = repo(rpc = rpc, authSession = store).claimInvite(INVITE_CODE, "wrong", null)

                result.shouldBeInstanceOf<AppResult.Failure>()
                verifySuspend(exactly(0)) { store.saveAuthTokens(any(), any(), any(), any()) }
            }
        }
    })
