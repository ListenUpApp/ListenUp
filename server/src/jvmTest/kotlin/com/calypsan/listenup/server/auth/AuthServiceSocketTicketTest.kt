@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.testPasswordResetService
import com.calypsan.listenup.server.testing.migratedTestDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Minting is what lets a browser authenticate at all, and the three per-call copies of
 * [AuthServiceImpl] are hand-written parameter lists — so a collaborator added to the constructor
 * is silently dropped by any copy that forgets it. That is not hypothetical: the socket ticket store
 * was dropped by all three on first write, and the only symptom was the browser's authed mount
 * quietly 401ing with nothing in the logs, because a service with no store cannot mint.
 */
class AuthServiceSocketTicketTest :
    FunSpec({
        val pepper = "x".repeat(32).toByteArray()
        val clock = FixedClock(Instant.parse("2026-05-02T12:00:00Z"))

        fun newSvc(store: SocketTicketStore?): AuthServiceImpl {
            val db = migratedTestDatabase().db
            val sessions =
                SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)
            val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, clock)
            return AuthServiceImpl(
                db = db,
                sessions = sessions,
                hasher = Argon2Limiter(PasswordHasher()),
                jwt = jwt,
                sessionIssuer = SessionIssuer(sessions, jwt, clock),
                clock = clock,
                settings = ServerSettingsRepository(db, default = RegistrationPolicy.OPEN),
                passwordResetService = testPasswordResetService(db, clock),
                socketTicketStore = store,
            )
        }

        test("a valid access token is traded for a ticket that redeems back to it") {
            runTest {
                val store = SocketTicketStore(clock)
                val svc = newSvc(store)
                val session = svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()

                val ticket =
                    svc
                        .issueSocketTicket(session.accessToken.value)
                        .shouldSucceed()
                        .value

                store.redeem(ticket) shouldBe session.accessToken.value
            }
        }

        test("a token that fails verification mints nothing") {
            runTest {
                val svc = newSvc(SocketTicketStore(clock))

                svc.issueSocketTicket("not.a.jwt").shouldBeInstanceOf<AppResult.Failure>()
            }
        }

        test("every per-call copy keeps the ticket store — a dropped one 401s the whole web client") {
            runTest {
                val store = SocketTicketStore(clock)
                val svc = newSvc(store)

                // The public RPC mount reaches issueSocketTicket through withRemoteHost, so a copy
                // that loses the store makes minting fail for exactly the callers that need it.
                svc.copyWith(PrincipalProvider.None).socketTicketStore shouldBe store
                svc.withUserAgent("ListenUp/web").socketTicketStore shouldBe store
                svc.withRemoteHost("192.168.1.10").socketTicketStore shouldBe store
            }
        }

        test("a copy can still mint, which is the property the browser actually depends on") {
            runTest {
                val store = SocketTicketStore(clock)
                val svc = newSvc(store)
                val session = svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()

                val ticket =
                    svc
                        .withRemoteHost("192.168.1.10")
                        .issueSocketTicket(session.accessToken.value)
                        .shouldSucceed()
                        .value

                store.redeem(ticket) shouldBe session.accessToken.value
            }
        }

        test("with no store configured, minting fails rather than pretending to succeed") {
            runTest {
                val svc = newSvc(null)
                val session = svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()

                svc.issueSocketTicket(session.accessToken.value).shouldBeInstanceOf<AppResult.Failure>()
            }
        }
    })

/** Asserts the [AppResult] is a Success and returns the unwrapped value. */
private fun <T> AppResult<T>.shouldSucceed(): T = shouldBeInstanceOf<AppResult.Success<T>>().data
