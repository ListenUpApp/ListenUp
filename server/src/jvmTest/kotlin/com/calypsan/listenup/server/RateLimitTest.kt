package com.calypsan.listenup.server

import com.calypsan.listenup.server.testing.publicAuthService

import com.calypsan.listenup.api.dto.auth.LoginRequest
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.ktor.server.testing.testApplication
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Verifies the per-IP login throttle actually bites on the RPC surface — the mount where
 * `AuthServiceImpl.enforceRate` is bound to a remote host, and therefore the only place the
 * limiter is live. Expansion to other buckets is redundant; the wiring is the same per-bucket.
 *
 * **Why a burst rather than "the 11th call"**, which is what this asserted before: the limiter is
 * a *token bucket*, not a fixed window. It refills continuously at
 * `capacity / refillPeriod` = 10 tokens per minute — **one token every six seconds**. Asserting
 * that call 11 is throttled therefore quietly required all ten preceding logins to finish inside a
 * six-second budget. Locally they took ~3.4s and it passed; on the contended two-core CI runner
 * they took longer, a token refilled, call 11 was allowed, and the test failed — a real timing
 * race, not an inert limiter.
 *
 * Firing a burst well past capacity removes the race: refill would have to keep pace with the
 * whole burst for *nothing* to be throttled, which needs `BURST - capacity` tokens ≈ a full minute
 * of elapsed time. The invariant under test is unchanged and arguably stated better — a burst from
 * one host gets throttled — and it no longer depends on how fast the runner is.
 */
class RateLimitTest :
    FunSpec({
        test("a login burst from one host is throttled over the RPC surface") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                // ONE proxy over ONE connection — a real client holds a single channel, and the
                // per-IP bucket is keyed on the remote host bound at registration time.
                val auth = publicAuthService()

                val outcomes =
                    (1..LOGIN_BURST).map {
                        auth.login(LoginRequest("nobody@x", "x".repeat(8)))
                    }

                val throttled = outcomes.filterIsInstance<AppResult.Failure>().map { it.error }

                // The limiter bit: a burst this far past capacity cannot be fully absorbed by refill.
                throttled.filterIsInstance<AuthError.RateLimited>().shouldNotBeEmpty()

                // The control. Without it a limiter that rejected *everything* — or a login path
                // that failed for some unrelated reason — would satisfy the assertion above. The
                // first `capacity` calls start against a full bucket, so they must get through the
                // throttle and fail only on credentials.
                outcomes.take(LOGIN_BUCKET_LIMIT).forEach { result ->
                    result
                        .shouldBeInstanceOf<AppResult.Failure>()
                        .error
                        .shouldBeInstanceOf<AuthError.InvalidCredentials>()
                }
            }
        }
    })

/** `AuthRateBucket.LOGIN`'s capacity — the number of calls that start against a full bucket. */
private const val LOGIN_BUCKET_LIMIT = 10

/**
 * Calls fired in the burst. Twice capacity, so absorbing the whole burst without throttling would
 * take `LOGIN_BURST - LOGIN_BUCKET_LIMIT` refilled tokens — about a minute of wall clock at one
 * token per six seconds. That is the margin that makes this test runner-speed-independent.
 */
private const val LOGIN_BURST = 20
