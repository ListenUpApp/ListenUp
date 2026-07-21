@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.migratedTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Pins the RPC registration-policy watch — [AuthServiceImpl.observeRegistrationPolicy], the
 * replacement for the retired `/api/v1/auth/registration-policy/stream` SSE route. Same contract,
 * new transport: the current persisted policy arrives immediately on subscribe, a live broadcast
 * lands instantly, an unchanged re-emit is silent (dedup), and the periodic persisted re-read is
 * the never-stranded net for a missed broadcast.
 */
class RegistrationPolicyRpcTest :
    FunSpec({
        val pepper = "x".repeat(32).toByteArray()
        val clock = FixedClock(Instant.parse("2026-05-02T12:00:00Z"))

        class Fixture(
            val svc: AuthServiceImpl,
            val broadcaster: RegistrationPolicyBroadcaster,
            val settings: ServerSettingsRepository,
        )

        fun newFixture(
            policy: RegistrationPolicy = RegistrationPolicy.OPEN,
            remoteHost: String? = null,
        ): Fixture {
            val db = migratedTestDatabase().db
            val hasher = PasswordHasher()
            val sessions =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)
            val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, clock)
            val settings = ServerSettingsRepository(db, default = policy)
            val broadcaster = RegistrationPolicyBroadcaster()
            val svc =
                AuthServiceImpl(
                    db = db,
                    sessions = sessions,
                    hasher = Argon2Limiter(hasher),
                    jwt = jwt,
                    sessionIssuer = SessionIssuer(sessions, jwt, clock),
                    clock = clock,
                    settings = settings,
                    remoteHost = remoteHost,
                    loginRateLimiter = remoteHost?.let { LoginRateLimiter(clock) },
                    registrationPolicyBroadcaster = broadcaster,
                )
            return Fixture(svc, broadcaster, settings)
        }

        fun Flow<RpcEvent<RegistrationPolicy>>.policies(): Flow<RegistrationPolicy> = mapNotNull { (it as? RpcEvent.Data)?.value }

        test("emits the current persisted policy immediately on subscribe") {
            runTest {
                val fix = newFixture(policy = RegistrationPolicy.CLOSED)

                fix.svc
                    .observeRegistrationPolicy()
                    .policies()
                    .first() shouldBe RegistrationPolicy.CLOSED
            }
        }

        test("a live broadcast lands after the current policy") {
            runTest {
                val fix = newFixture(policy = RegistrationPolicy.OPEN)

                val seen =
                    fix.svc
                        .observeRegistrationPolicy()
                        .policies()
                        .onEach { if (it == RegistrationPolicy.OPEN) fix.broadcaster.notify(RegistrationPolicy.CLOSED) }
                        .take(2)
                        .toList()

                seen shouldBe listOf(RegistrationPolicy.OPEN, RegistrationPolicy.CLOSED)
            }
        }

        test("an unchanged re-broadcast is silent — the stream dedups") {
            runTest {
                val fix = newFixture(policy = RegistrationPolicy.OPEN)

                val seen =
                    fix.svc
                        .observeRegistrationPolicy()
                        .policies()
                        .onEach {
                            if (it == RegistrationPolicy.OPEN) {
                                // Re-broadcast of the current value, then a real change. Without dedup
                                // the duplicate OPEN would be the second collected item.
                                fix.broadcaster.notify(RegistrationPolicy.OPEN)
                                fix.broadcaster.notify(RegistrationPolicy.APPROVAL_QUEUE)
                            }
                        }.take(2)
                        .toList()

                seen shouldBe listOf(RegistrationPolicy.OPEN, RegistrationPolicy.APPROVAL_QUEUE)
            }
        }

        test("the periodic persisted re-read picks up an out-of-band change — never stranded") {
            runTest {
                val fix = newFixture(policy = RegistrationPolicy.OPEN)

                val seen =
                    fix.svc
                        .observeRegistrationPolicy()
                        .policies()
                        .onEach {
                            // Persist a change WITHOUT a broadcast — only the poll can deliver it.
                            // runTest's virtual time skips the poll cadence instantly.
                            if (it == RegistrationPolicy.OPEN) {
                                fix.settings.setRegistrationPolicy(RegistrationPolicy.CLOSED)
                            }
                        }.take(2)
                        .toList()

                seen shouldBe listOf(RegistrationPolicy.OPEN, RegistrationPolicy.CLOSED)
            }
        }

        test("observeRegistrationPolicy throttles after the OBSERVE_REGISTRATION_POLICY ceiling from one host") {
            runTest {
                val fix = newFixture(remoteHost = "10.0.0.9")

                repeat(AuthRateBucket.OBSERVE_REGISTRATION_POLICY.perMinuteLimit) {
                    // The watch never completes on its own; first() cancels the subscription after
                    // the initial policy, mirroring a client that connects and leaves.
                    fix.svc
                        .observeRegistrationPolicy()
                        .first()
                        .shouldBeInstanceOf<RpcEvent.Data<RegistrationPolicy>>()
                }

                val throttled = fix.svc.observeRegistrationPolicy().toList()
                val error = throttled.single().shouldBeInstanceOf<RpcEvent.Error>()
                error.error.shouldBeInstanceOf<AuthError.RateLimited>()
            }
        }
    })
