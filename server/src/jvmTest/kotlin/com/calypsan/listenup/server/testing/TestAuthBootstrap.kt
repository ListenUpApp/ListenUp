package com.calypsan.listenup.server.testing

import com.calypsan.listenup.server.testing.publicAuthService

import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.result.AppResult
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.request.bearerAuth
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.rpc.krpc.ktor.client.installKrpc
import kotlinx.rpc.krpc.ktor.client.rpc
import kotlinx.rpc.krpc.ktor.client.rpcConfig
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.withService

/**
 * The one way a server test provisions an authenticated caller.
 *
 * Tests used to bootstrap over `POST /api/v1/auth/{setup,register,login}` — around 49 files, each
 * carrying its own private `runSetup` / `setupRoot` copy. That made the REST auth twins
 * *test-only surface living in production code*: no shipped client ever called them (first-party
 * auth rides `AuthServicePublic` on `/api/rpc/public`), so the endpoints existed to serve the test
 * suite alone. They are gone, and this is what replaced them — the same bootstrap, over the
 * transport production actually uses, written once.
 *
 * Every helper here opens a short-lived unauthenticated proxy against the harness's in-process
 * public RPC mount, so a test exercises the real auth stack (real password hashing, real session
 * rows, real JWTs) rather than a fake principal. Route tests that only need *an* authenticated
 * caller and don't care about the auth stack should keep using [TestAuthProvider] instead — it
 * skips JWT minting entirely and is much faster.
 */
data class BootstrappedUser(
    val token: String,
    val userId: String,
    val session: AuthSession,
)

/** Opens an unauthenticated [AuthServicePublic] proxy against the harness's public RPC mount. */
suspend fun ApplicationTestBuilder.publicAuthService(): AuthServicePublic {
    val rpcClient =
        createClient {
            install(WebSockets)
            installKrpc()
        }
    return rpcClient
        .rpc("ws://localhost/api/rpc/public") {
            rpcConfig { serialization { json(contractJson) } }
        }.withService<AuthServicePublic>()
}

/**
 * Runs first-user setup and returns the ROOT caller.
 *
 * [email], [password] and [displayName] default to the values the old per-file helpers used, so a
 * converted test keeps its existing seeded identity.
 */
suspend fun ApplicationTestBuilder.setupRootUser(
    email: String = "root@x",
    password: String = "x".repeat(8),
    displayName: String = "Root",
): BootstrappedUser {
    val session =
        publicAuthService()
            .setupRoot(RegisterRequest(email, password, displayName))
            .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
            .data
    return BootstrappedUser(session.accessToken.value, session.user.id.value, session)
}

/**
 * Registers a member under the instance's current registration policy and returns the caller.
 *
 * Fails the test if the policy did not authenticate the applicant outright (i.e. the instance is on
 * an approval-gated policy) — a caller that wants the pending-approval branch should call
 * [publicAuthService] and assert on [RegisterResult] itself.
 */
suspend fun ApplicationTestBuilder.registerMemberUser(
    name: String,
    email: String = "$name@x",
    password: String = "y".repeat(8),
): BootstrappedUser {
    val session =
        publicAuthService()
            .register(RegisterRequest(email, password, name))
            .shouldBeInstanceOf<AppResult.Success<RegisterResult>>()
            .data
            .shouldBeInstanceOf<RegisterResult.Authenticated>()
            .session
    return BootstrappedUser(session.accessToken.value, session.user.id.value, session)
}

/** Logs an existing user in and returns the caller. */
suspend fun ApplicationTestBuilder.loginUser(
    email: String,
    password: String,
): BootstrappedUser {
    val session =
        publicAuthService()
            .login(LoginRequest(email, password))
            .shouldBeInstanceOf<AppResult.Success<AuthSession>>()
            .data
    return BootstrappedUser(session.accessToken.value, session.user.id.value, session)
}

/**
 * Opens an authed RPC proxy for [T] bound to [token]'s principal.
 *
 * The counterpart to [publicAuthService] for services behind the JWT wall. Tests that used to
 * drive an admin action over a REST mirror (`PATCH /api/v1/admin/users/{id}`, …) call the RPC
 * service through this instead — same principal binding, same guard, no parallel transport.
 */
suspend inline fun <reified T : Any> ApplicationTestBuilder.authedService(token: String): T {
    val rpcClient =
        createClient {
            install(WebSockets)
            installKrpc()
        }
    return rpcClient
        .rpc("ws://localhost/api/rpc/authed") {
            rpcConfig { serialization { json(contractJson) } }
            bearerAuth(token)
        }.withService<T>()
}
