package com.calypsan.listenup.client.e2e

import com.calypsan.listenup.api.AdminUserService
import com.calypsan.listenup.api.AuthServiceAuthed
import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.PasswordResetDecisionOutcome
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.toWebSocketScheme
import com.calypsan.listenup.client.di.e2e.DiWiredClientFixture
import com.calypsan.listenup.client.domain.repository.PasswordResetRepository
import com.calypsan.listenup.client.domain.repository.ServerConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.bearerAuth
import kotlinx.coroutines.runBlocking
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json as krpcJson
import kotlinx.rpc.withService

/**
 * End-to-end proof of the admin-approval password-reset flow (Task 16 of the password-reset
 * plan) against the REAL `:server`, in-process, driving the REAL client
 * [PasswordResetRepository] resolved from [DiWiredClientFixture]'s production-wired Koin graph
 * — the same repository a future `ForgotPasswordViewModel` will call.
 *
 * The admin side of this flow has no client repository yet (that is a later task), so the
 * approval step goes straight at the server's real [AdminUserService] over the real authed RPC
 * mount — a raw kotlinx.rpc proxy authenticated as a genuine ROOT principal, mirroring the
 * pattern [com.calypsan.listenup.client.admin.ImportRpcE2ETest] already uses for an admin-gated
 * surface with no client-side wiring. That exercises the real route, the real admin-role gate,
 * and the real [com.calypsan.listenup.server.services.PasswordResetService] — not a shortcut
 * around them.
 *
 * "Two devices" is modeled as two independent `login()` calls minting two independent sessions
 * (distinct session ids / refresh-token families) — each verified live by an authed `currentUser()`
 * call over its own bearer-scoped RPC connection, since a JWT's bearer identity is fixed at the
 * WebSocket handshake and cannot be swapped mid-connection. Checking that a revoked session is
 * dead goes through [assertSessionDead] rather than a plain RPC call: a revoked session is
 * rejected by [com.calypsan.listenup.server.plugins.installJwtAuth]'s `bearer` provider at the
 * WebSocket *handshake*, before any RPC frame is exchanged, so the raw proxy this file uses (with
 * none of production [com.calypsan.listenup.client.data.remote.RpcChannel]'s transport-failure
 * folding) surfaces it as a thrown exception rather than a graceful [AppResult.Failure].
 */
class PasswordResetE2ETest :
    FunSpec({

        test("request → approve → complete: the old password dies, the new one works, sessions are gone") {
            runBlocking {
                val fixture = autoClose(DiWiredClientFixture.start())
                val koin = fixture.koin.koin
                val baseUrl = koin.get<ServerConfig>().getActiveUrl()!!.value
                val passwordResetRepo = koin.get<PasswordResetRepository>()

                val rpcClient = HttpClient(OkHttp) { installKrpc() }
                try {
                    val publicProxy = rpcClient.authPublicProxy(baseUrl)

                    // ROOT stands in as the admin who reviews and approves the reset — ROOT
                    // passes the same admin-role gate ADMIN does.
                    val rootAccessToken =
                        publicProxy
                            .setupRoot(
                                RegisterRequest(
                                    email = "root@password-reset-e2e.test",
                                    password = "root-password-12345",
                                    displayName = "Root",
                                ),
                            ).requireSuccess()
                            .accessToken.value

                    // Ada is an ordinary ACTIVE member — the fixture's OPEN registration policy
                    // admits her immediately, no admin approval needed to create the account.
                    val email = "ada@password-reset-e2e.test"
                    val oldPassword = "correct horse battery staple"
                    publicProxy
                        .register(RegisterRequest(email = email, password = oldPassword, displayName = "Ada"))
                        .requireSuccess()
                        .shouldBeInstanceOf<RegisterResult.Authenticated>()

                    // Ada is signed in on two devices — two independent sessions.
                    val deviceAToken =
                        publicProxy
                            .login(LoginRequest(email = email, password = oldPassword))
                            .requireSuccess()
                            .accessToken.value
                    val deviceBToken =
                        publicProxy
                            .login(LoginRequest(email = email, password = oldPassword))
                            .requireSuccess()
                            .accessToken.value

                    // Both sessions genuinely work before the reset.
                    rpcClient.authedProxy(baseUrl, deviceAToken).currentUser().requireSuccess()
                    rpcClient.authedProxy(baseUrl, deviceBToken).currentUser().requireSuccess()

                    // Ada opens a reset request through the REAL client repository under test.
                    val ticket = passwordResetRepo.requestReset(email).requireSuccess()

                    // The admin approves it and receives the out-of-band code — the display form
                    // (`ABCD-2345`) an admin actually reads aloud, exactly what a user would type back.
                    val decision =
                        rpcClient
                            .adminUserProxy(baseUrl, rootAccessToken)
                            .decidePasswordReset(ticket.ticketId, approved = true)
                            .requireSuccess()
                    val code = decision.shouldBeInstanceOf<PasswordResetDecisionOutcome.Approved>().code

                    // Ada's device completes the reset with its retained claim plus the code a
                    // human conveyed to her.
                    val newPassword = "a brand new password entirely"
                    passwordResetRepo.completeReset(ticket.ticketId, code, newPassword).requireSuccess()

                    // The old password is dead...
                    publicProxy
                        .login(LoginRequest(email = email, password = oldPassword))
                        .shouldBeInstanceOf<AppResult.Failure>()

                    // ...the new one works...
                    publicProxy
                        .login(LoginRequest(email = email, password = newPassword))
                        .requireSuccess()

                    // ...and BOTH prior sessions are dead: completion revoked every session on the account.
                    rpcClient.assertSessionDead(baseUrl, deviceAToken)
                    rpcClient.assertSessionDead(baseUrl, deviceBToken)
                } finally {
                    rpcClient.close()
                }
            }
        }

        test("a reset request leaves the account usable while it is pending") {
            runBlocking {
                val fixture = autoClose(DiWiredClientFixture.start())
                val koin = fixture.koin.koin
                val baseUrl = koin.get<ServerConfig>().getActiveUrl()!!.value
                val passwordResetRepo = koin.get<PasswordResetRepository>()

                val rpcClient = HttpClient(OkHttp) { installKrpc() }
                try {
                    val publicProxy = rpcClient.authPublicProxy(baseUrl)

                    // Bootstraps the instance — unused beyond that in this test, which asserts
                    // nothing about admin approval, only that opening a request is non-disruptive.
                    publicProxy
                        .setupRoot(
                            RegisterRequest(
                                email = "root@password-reset-pending-e2e.test",
                                password = "root-password-12345",
                                displayName = "Root",
                            ),
                        ).requireSuccess()

                    val email = "bea@password-reset-pending-e2e.test"
                    val password = "an already established password"
                    publicProxy
                        .register(RegisterRequest(email = email, password = password, displayName = "Bea"))
                        .requireSuccess()
                        .shouldBeInstanceOf<RegisterResult.Authenticated>()

                    val existingSessionToken =
                        publicProxy
                            .login(LoginRequest(email = email, password = password))
                            .requireSuccess()
                            .accessToken.value

                    // The existing session genuinely works before the request.
                    rpcClient.authedProxy(baseUrl, existingSessionToken).currentUser().requireSuccess()

                    passwordResetRepo.requestReset(email).requireSuccess()

                    // Still works — a pending reset must not disturb someone mid-book on another
                    // device. Only completion (not a mere request) revokes anything.
                    rpcClient.authedProxy(baseUrl, existingSessionToken).currentUser().requireSuccess()
                } finally {
                    rpcClient.close()
                }
            }
        }
    })

// ── raw RPC proxies ──────────────────────────────────────────────────────────
//
// The client has no repository for the admin-decision side of this flow yet, so these reach the
// real server surfaces directly — the same pattern com.calypsan.listenup.client.admin.ImportRpcE2ETest
// uses for an admin-gated surface with no client-side wiring. Each bearer identity gets its own
// connection: a kotlinx.rpc WebSocket's auth is fixed at the handshake and cannot be swapped
// mid-connection, which is also exactly what "two devices" means here — two independent
// sessions, each verified over its own socket.

private suspend fun HttpClient.authPublicProxy(baseUrl: String): AuthServicePublic =
    rpc("${toWebSocketScheme(baseUrl)}/api/rpc/public") {
        rpcConfig { serialization { krpcJson(contractJson) } }
    }.withService<AuthServicePublic>()

private suspend fun HttpClient.authedProxy(
    baseUrl: String,
    accessToken: String,
): AuthServiceAuthed =
    rpc("${toWebSocketScheme(baseUrl)}/api/rpc/authed") {
        rpcConfig { serialization { krpcJson(contractJson) } }
        bearerAuth(accessToken)
    }.withService<AuthServiceAuthed>()

private suspend fun HttpClient.adminUserProxy(
    baseUrl: String,
    accessToken: String,
): AdminUserService =
    rpc("${toWebSocketScheme(baseUrl)}/api/rpc/authed") {
        rpcConfig { serialization { krpcJson(contractJson) } }
        bearerAuth(accessToken)
    }.withService<AdminUserService>()

/**
 * Asserts that [accessToken]'s session no longer works. A revoked session is rejected at the
 * WebSocket handshake (401, before any RPC frame) — see the class KDoc — so the raw proxy this
 * file uses surfaces that as a thrown exception, not an [AppResult.Failure]. Either shape (a
 * thrown exception, or a completed call that itself returns [AppResult.Failure]) proves the
 * session is dead.
 */
private suspend fun HttpClient.assertSessionDead(
    baseUrl: String,
    accessToken: String,
) {
    val outcome = runCatching { authedProxy(baseUrl, accessToken).currentUser() }
    val sessionIsDead = outcome.isFailure || outcome.getOrNull() is AppResult.Failure
    require(sessionIsDead) { "expected the session to be dead but currentUser() returned $outcome" }
}

/** Unwraps a business [AppResult.Success], failing the test with the actual value otherwise. */
private fun <T> AppResult<T>.requireSuccess(): T {
    require(this is AppResult.Success) { "expected Success but got $this" }
    return data
}
