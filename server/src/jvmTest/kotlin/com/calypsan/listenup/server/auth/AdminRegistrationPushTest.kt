@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.push.PushPayload
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.push.PushNotifier
import com.calypsan.listenup.server.push.PushWatchKind
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.testPasswordResetService
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The link that closes the registration loop (#1068).
 *
 * `RegistrationDecision` already wakes the registrant once an admin decides — but until this
 * existed, nothing woke the admin who has to decide. An admin with a closed app learned of a
 * pending request only by happening to open the Admin screen, so a request could sit unseen
 * indefinitely and every downstream notification waited on a decision nobody had been asked for.
 *
 * These tests pin the three properties that make the fan-out safe as well as correct: it reaches
 * every admin, it never reaches anyone else, and it cannot take a registration down with it.
 */
class AdminRegistrationPushTest :
    FunSpec({
        val pepper = "x".repeat(32).toByteArray()
        val clock = FixedClock(Instant.parse("2026-05-02T12:00:00Z"))

        test("a pending registration notifies every admin, and nobody else") {
            withSqlDatabase {
                val notifier = RecordingPushNotifier()
                val svc = authService(sql, RegistrationPolicy.APPROVAL_QUEUE, clock, pepper, notifier)

                runTest {
                    val root = svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                    sql.seedTestUser("second-admin", UserRoleColumn.ADMIN)
                    // A plain member must NOT be told — they cannot act on it, and a private
                    // server's membership is not everyone's business.
                    sql.seedTestUser("plain-member", UserRoleColumn.MEMBER)
                    // A departed admin must NOT be told either: the row is tombstoned, and their
                    // old devices should stop hearing about the server entirely.
                    sql.seedTestUser("ex-admin", UserRoleColumn.ADMIN, deletedAt = 1L)
                    notifier.sent.clear()

                    val pending =
                        svc
                            .register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                            .shouldSucceed()
                            .shouldBeInstanceOf<RegisterResult.PendingApproval>()

                    notifier.sent.map { it.first } shouldContainExactlyInAnyOrder
                        listOf(root.user.id.value, "second-admin")

                    // IDs only — the payload names nobody. A push naming everyone who asks to join
                    // a private server would leak exactly what self-hosting exists to protect.
                    notifier.sent.forEach { (_, payload) ->
                        payload.shouldBeInstanceOf<PushPayload.RegistrationApproval>().userId shouldBe
                            pending.userId.value
                    }
                }
            }
        }

        // The counter-case that stops the test above being trivially satisfiable: an OPEN instance
        // creates an ACTIVE user with nothing to approve, so waking every admin would be noise.
        test("an OPEN instance notifies nobody — there is no decision to make") {
            withSqlDatabase {
                val notifier = RecordingPushNotifier()
                val svc = authService(sql, RegistrationPolicy.OPEN, clock, pepper, notifier)

                runTest {
                    svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                    notifier.sent.clear()

                    svc.register(RegisterRequest("alice@x", "x".repeat(8), "Alice")).shouldSucceed()

                    notifier.sent.shouldBeEmpty()
                }
            }
        }

        // ⛔ The property that matters most in production. Push is a wake-up accelerant; the
        // request itself lives in the synced admin roster. A relay outage, a dead token store, or
        // a thrown exception must cost the notification and nothing else — never the registration.
        // Without this, an unreachable relay would stop people signing up at all.
        test("a notifier that throws does not fail the registration") {
            withSqlDatabase {
                val svc = authService(sql, RegistrationPolicy.APPROVAL_QUEUE, clock, pepper, ThrowingPushNotifier)

                runTest {
                    svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()

                    val out = svc.register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))

                    out.shouldSucceed().shouldBeInstanceOf<RegisterResult.PendingApproval>()
                }
            }
        }

        test("no notifier bound at all is simply quiet, not broken") {
            withSqlDatabase {
                // Forks without a relay assemble the auth module with no push module at all.
                val svc = authService(sql, RegistrationPolicy.APPROVAL_QUEUE, clock, pepper, notifier = null)

                runTest {
                    svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()

                    svc
                        .register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                        .shouldSucceed()
                        .shouldBeInstanceOf<RegisterResult.PendingApproval>()
                }
            }
        }
    })

/** Captures `(userId, payload)` per send so the fan-out set can be asserted exactly. */
private class RecordingPushNotifier : PushNotifier {
    val sent = mutableListOf<Pair<String, PushPayload>>()

    override suspend fun notify(
        userId: String,
        payload: PushPayload,
    ) {
        sent += userId to payload
    }

    override suspend fun notifyWatch(
        kind: PushWatchKind,
        key: String,
        payload: PushPayload,
    ) = Unit
}

/** Stands in for a relay outage or a dead token store. */
private object ThrowingPushNotifier : PushNotifier {
    override suspend fun notify(
        userId: String,
        payload: PushPayload,
    ): Unit = error("relay unreachable")

    override suspend fun notifyWatch(
        kind: PushWatchKind,
        key: String,
        payload: PushPayload,
    ) = Unit
}

@OptIn(ExperimentalTime::class)
private fun authService(
    db: ListenUpDatabase,
    policy: RegistrationPolicy,
    clock: FixedClock,
    pepper: ByteArray,
    notifier: PushNotifier?,
): AuthServiceImpl {
    val sessions = SessionService(db, RefreshTokenHasher(pepper), RefreshTokenGenerator(), clock = clock)
    val jwt = JwtConfiguration("x".repeat(32), "listenup", "listenup-client", 15.minutes, clock)
    return AuthServiceImpl(
        db = db,
        sessions = sessions,
        hasher = Argon2Limiter(PasswordHasher()),
        jwt = jwt,
        sessionIssuer = SessionIssuer(sessions, jwt, clock),
        clock = clock,
        settings = ServerSettingsRepository(db, default = policy),
        passwordResetService = testPasswordResetService(db, clock),
        pushNotifier = notifier,
    )
}

/** Asserts the [AppResult] is a Success and returns the unwrapped value. */
private fun <T> AppResult<T>.shouldSucceed(): T = shouldBeInstanceOf<AppResult.Success<T>>().data
