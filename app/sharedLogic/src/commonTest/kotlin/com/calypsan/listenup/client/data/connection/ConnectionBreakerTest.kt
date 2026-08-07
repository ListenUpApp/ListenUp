package com.calypsan.listenup.client.data.connection

import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.TransportError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.RpcDispatch
import com.calypsan.listenup.client.data.remote.RpcPolicy
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The transport already KNOWS when it is dead — [ConnectionEvidence] classifies every unary outcome
 * at the [com.calypsan.listenup.client.data.remote.RpcChannel] boundary. Until now it was a pure
 * sink: nothing consulted it before committing a caller to a 15-second wait.
 *
 * That is how the 2026-08-07 resume stall got its length. The firehose watchdog had already logged
 * "silent for 75000ms — treating as half-open" at 08:32:28.972, and the app then issued call after
 * call, each paying the full bound, for another minute.
 *
 * The breaker is deliberately NOT a classic open/half-open state machine. Calls are never refused —
 * they are merely bounded shorter, which means an ordinary call IS the probe: if the server is back,
 * it answers inside the short bound and the breaker closes on the success. The only case that needs
 * explicit help is a link slow enough that every short-bounded call times out, so every Nth call
 * while open goes out at the caller's full bound.
 */
class ConnectionBreakerTest :
    FunSpec({

        fun transportFailure() = AppResult.Failure(TransportError.Timeout())

        test("a healthy channel gets the caller's bound untouched") {
            val evidence = ConnectionEvidence()

            evidence.recordOutcome(AppResult.Success(Unit))

            evidence.boundFor(15.seconds) shouldBe 15.seconds
        }

        test("consecutive transport failures collapse the bound") {
            val evidence = ConnectionEvidence()

            repeat(CONSECUTIVE_FAILURES_TO_OPEN) { evidence.recordOutcome(transportFailure()) }

            evidence.boundFor(15.seconds) shouldBe OPEN_CIRCUIT_BOUND
        }

        test("a single failure is not enough — one blip must not degrade every call") {
            val evidence = ConnectionEvidence()

            evidence.recordOutcome(transportFailure())

            evidence.boundFor(15.seconds) shouldBe 15.seconds
        }

        test("any answer from the server re-closes the breaker") {
            val evidence = ConnectionEvidence()
            repeat(CONSECUTIVE_FAILURES_TO_OPEN) { evidence.recordOutcome(transportFailure()) }

            // Not a success — an auth rejection. It still proves the server ANSWERED.
            evidence.recordOutcome(AppResult.Failure(AuthError.SessionExpired()))

            evidence.boundFor(15.seconds) shouldBe 15.seconds
        }

        test("the breaker never lengthens a caller's shorter bound") {
            val evidence = ConnectionEvidence()
            repeat(CONSECUTIVE_FAILURES_TO_OPEN) { evidence.recordOutcome(transportFailure()) }

            // The resume-position fetch asks for 800ms precisely because it is on the path to audio.
            // An open breaker must not hand it something longer.
            evidence.boundFor(800.milliseconds) shouldBe 800.milliseconds
        }

        test("the breaker is applied at the RpcChannel boundary, so EVERY service inherits it") {
            runTest {
                val evidence = ConnectionEvidence()
                repeat(CONSECUTIVE_FAILURES_TO_OPEN) { evidence.recordOutcome(transportFailure()) }
                val dispatch = BoundRecordingDispatch()
                // Deliberately not a real service: the guarantee is a property of the boundary, not
                // of any one @Rpc interface. One shared ConnectionEvidence + one dispatch path means
                // search, sync, admin, auth and playback all inherit this with no per-service wiring.
                val channel = RpcChannel(dispatch, RpcPolicy.Authed, evidence = evidence)

                channel.call { AppResult.Success(Unit) }

                dispatch.lastTimeout shouldBe OPEN_CIRCUIT_BOUND
            }
        }

        test("every Nth call while open probes at the full bound, so a merely-slow link recovers") {
            val evidence = ConnectionEvidence()
            repeat(CONSECUTIVE_FAILURES_TO_OPEN) { evidence.recordOutcome(transportFailure()) }

            val bounds = List(PROBE_EVERY * 2) { evidence.boundFor(15.seconds) }

            // Without this, a link whose RTT exceeds OPEN_CIRCUIT_BOUND could never answer in time,
            // so the breaker would latch open forever and the app would look permanently offline.
            bounds.count { it == 15.seconds } shouldBe 2
        }
    })

/** Records the timeout the channel actually handed down, for the boundary-wiring test. */
private class BoundRecordingDispatch : RpcDispatch<Unit> {
    var lastTimeout: Duration? = null

    override suspend fun <R> call(
        timeout: Duration,
        idempotent: Boolean,
        block: suspend (Unit) -> R,
    ): R {
        lastTimeout = timeout
        return block(Unit)
    }

    override fun <R> streaming(subscribe: suspend (Unit) -> Flow<R>): Flow<R> = emptyFlow()

    override suspend fun invalidate() = Unit
}
