package com.calypsan.listenup.server.rpcguard

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.api.result.AppResult
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Pins `stampAndLogFailure` — the guard seam that makes a RETURNED domain failure carry the request's
 * correlation id (finding #5). A service that returns `AppResult.Failure(SessionExpired())` carries
 * `correlationId = null`; without this the "operator's log line links to the user's error" contract is
 * vacuous on the RPC transport.
 */
class GuardStampTest :
    FunSpec({
        val log = KotlinLogging.logger("test")

        test("stamps the request cid onto a returned domain failure whose correlationId is null") {
            val original = AuthError.SessionExpired()
            original.correlationId shouldBe null
            val failure: AppResult<Int> = AppResult.Failure(original)

            val stamped = failure.stampAndLogFailure("cid-123", log, "FakeService", "foo")

            val f = stamped.shouldBeInstanceOf<AppResult.Failure>()
            f.error.correlationId shouldBe "cid-123"
            // Type + user-facing fields are preserved; only the id is added.
            val expired = f.error.shouldBeInstanceOf<AuthError.SessionExpired>()
            expired.code shouldBe "AUTH_SESSION_EXPIRED"
        }

        test("does not overwrite a server-issued correlationId already present on the failure") {
            val failure: AppResult<Int> = AppResult.Failure(InternalError(correlationId = "server-cid"))

            val stamped = failure.stampAndLogFailure("request-cid", log, "S", "m")

            val f = stamped.shouldBeInstanceOf<AppResult.Failure>()
            f.error.correlationId shouldBe "server-cid"
        }

        test("passes a success result through untouched") {
            val success: AppResult<Int> = AppResult.Success(42)

            val out = success.stampAndLogFailure("cid", log, "S", "m")

            out.shouldBeInstanceOf<AppResult.Success<Int>>().data shouldBe 42
        }
    })
