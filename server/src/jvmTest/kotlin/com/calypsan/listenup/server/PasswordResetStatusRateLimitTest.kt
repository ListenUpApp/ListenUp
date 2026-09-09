package com.calypsan.listenup.server

import com.calypsan.listenup.api.dto.auth.PasswordResetStatusEvent
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.server.testing.publicAuthService
import com.calypsan.listenup.server.testing.useIsolatedTestConfig
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.core.spec.style.FunSpec
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.first

/**
 * Verifies the per-IP throttle on `observePasswordResetStatus` — the last un-bucketed method on
 * the public auth mount, and the one whose cost is *not* per call: every open subscription holds a
 * poll loop that re-reads the ticket on a fixed cadence and never completes while it is `PENDING`.
 * An unbounded stream of subscribe attempts is therefore a resource-exhaustion vector in its own
 * right, exactly as it is for its two already-bucketed `observe*` siblings.
 *
 * **Why a burst rather than "the 21st call"** — the reasoning is `RateLimitTest`'s, verbatim in
 * substance: the limiter is a *token bucket*, not a fixed window. It refills continuously at
 * `capacity / refillPeriod`, so asserting that call `capacity + 1` is throttled quietly requires
 * every preceding call to finish inside one refill interval — which holds locally and fails on a
 * contended CI runner, a real timing race rather than an inert limiter. Firing well past capacity
 * removes it: absorbing the whole burst would need `BURST - capacity` tokens to refill mid-burst,
 * about a minute of wall clock.
 */
class PasswordResetStatusRateLimitTest :
    FunSpec({
        test("an observePasswordResetStatus burst from one host is throttled") {
            testApplication {
                useIsolatedTestConfig()
                application { module() }

                // ONE proxy over ONE connection — the per-IP bucket keys on the remote host bound
                // at registration time, so every subscription below shares a bucket.
                val auth = publicAuthService()

                // Only the FIRST emission is taken: for a pending (or phantom) ticket the stream
                // polls forever, and the throttle decision — pass or deny — is made before it.
                val firstEvents =
                    (1..OBSERVE_BURST).map {
                        auth.observePasswordResetStatus("ticket-that-was-never-issued").first()
                    }

                // The limiter bit: a burst this far past capacity cannot be fully absorbed by refill.
                firstEvents
                    .filterIsInstance<RpcEvent.Error>()
                    .map { it.error }
                    .filterIsInstance<AuthError.RateLimited>()
                    .shouldNotBeEmpty()

                // The control. Without it, a limiter that rejected *everything* — or a stream that
                // errored for some unrelated reason — would satisfy the assertion above. The first
                // `capacity` subscriptions start against a full bucket, so they must get through
                // and emit the oracle-free PENDING status a phantom ticket always reports.
                firstEvents.take(OBSERVE_BUCKET_LIMIT).forEach { event ->
                    event.shouldBeInstanceOf<RpcEvent.Data<PasswordResetStatusEvent>>()
                }
            }
        }
    })

/** `AuthRateBucket.OBSERVE_PASSWORD_RESET_STATUS`'s capacity — the subscriptions that start full. */
private const val OBSERVE_BUCKET_LIMIT = 20

/**
 * Subscriptions fired in the burst. Twice capacity, so absorbing it all without throttling would
 * take `OBSERVE_BURST - OBSERVE_BUCKET_LIMIT` refilled tokens — about a minute of wall clock at
 * one token every three seconds. That margin is what makes this runner-speed-independent.
 */
private const val OBSERVE_BURST = 40
