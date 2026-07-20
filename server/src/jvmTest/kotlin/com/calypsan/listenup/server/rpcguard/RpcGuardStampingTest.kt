package com.calypsan.listenup.server.rpcguard

import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.dto.auth.AuthSession
import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.api.dto.auth.RefreshRequest
import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationStatusEvent
import com.calypsan.listenup.api.dto.scanner.ScanResult
import com.calypsan.listenup.api.dto.scanner.ScanResultSummary
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/**
 * Behaviour proof for the guard's correlation-id stamping (finding #5) and its synchronous-flow-throw
 * containment (finding #6a), exercised through the production KSP-generated `guard(...)` wrappers.
 */
class RpcGuardStampingTest :
    FunSpec({

        test("a RETURNED domain failure comes back carrying a correlation id (finding #5)") {
            // The service returns AppResult.Failure(SessionExpired()) with correlationId = null.
            val guarded = guard(SessionExpiredAuthService())

            val result =
                guarded.login(LoginRequest(email = "u@example.com", password = "password123"))

            val failure = result.shouldBeInstanceOf<AppResult.Failure>()
            // Type + user-facing message preserved; the guard injected the request's correlation id.
            val expired = failure.error.shouldBeInstanceOf<AuthError.SessionExpired>()
            expired.correlationId.shouldNotBeNull()
            expired.correlationId!!.length shouldBe 36 // UUID-shaped fresh id when no MDC is present
        }

        test("a streaming method that throws SYNCHRONOUSLY becomes a sanitized RpcEvent.Error (finding #6a)") {
            // observeProgress() throws while CONSTRUCTING the flow — with the old `delegate.x().catch{}`
            // shape this escaped; the `flow { emitAll(...) }.catch` wrap now contains it.
            val guarded = guard(SyncThrowScannerService())

            val firstEvent = guarded.observeProgress().first()

            val error = firstEvent.shouldBeInstanceOf<RpcEvent.Error>()
            val internal = error.error.shouldBeInstanceOf<InternalError>()
            // No server-internal detail crosses the wire: only the correlation id.
            internal.correlationId.shouldNotBeNull()
            internal.cause shouldBe null
            internal.debugInfo shouldBe null
        }
    })

/** Returns a typed domain failure with `correlationId = null`, as a real service would. */
private class SessionExpiredAuthService : AuthServicePublic {
    override suspend fun login(request: LoginRequest): AppResult<AuthSession> = AppResult.Failure(AuthError.SessionExpired())

    override suspend fun register(request: RegisterRequest): AppResult<RegisterResult> = error("not used in this test")

    override suspend fun setupRoot(request: RegisterRequest): AppResult<AuthSession> = error("not used in this test")

    override suspend fun refreshSession(request: RefreshRequest): AppResult<AuthSession> = error("not used in this test")

    override fun observeRegistrationStatus(userId: String): Flow<RpcEvent<RegistrationStatusEvent>> =
        error("not used in this test")
}

/** `observeProgress()` throws synchronously while building the flow (a precondition/DI-style fault). */
private class SyncThrowScannerService : ScannerService {
    override suspend fun scanFull(): AppResult<ScanResultSummary> = error("not used in this test")

    override suspend fun lastScanResult(): AppResult<ScanResult> = error("not used in this test")

    override fun observeProgress(): Flow<RpcEvent<ScanEvent>> = throw IllegalStateException("boom during flow construction")
}
