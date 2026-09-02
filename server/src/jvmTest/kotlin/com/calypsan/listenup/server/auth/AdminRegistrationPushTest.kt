@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.auth

import com.calypsan.listenup.api.dto.auth.RegisterRequest
import com.calypsan.listenup.api.dto.auth.RegisterResult
import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.push.PushPayload
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.notifications.NotificationEmitter
import com.calypsan.listenup.server.notifications.NotificationPrefsRepository
import com.calypsan.listenup.server.push.PushNotifier
import com.calypsan.listenup.server.push.PushWatchKind
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.sync.notificationFixture
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.testPasswordResetService
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
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
                val svc = authService(sql, RegistrationPolicy.APPROVAL_QUEUE, clock, pepper, emitter(notifier))

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

                    // The durable half: each admin also gets a registration_approval inbox row
                    // (the push is only the wake-up accelerant), and nobody else does.
                    listOf(root.user.id.value, "second-admin").forEach { adminId ->
                        val ids =
                            sql.notificationsQueries
                                .selectLiveIdsForUserOldestFirst(adminId, 10)
                                .executeAsList()
                        ids shouldHaveSize 1
                        sql.notificationsQueries
                            .selectById(ids.first())
                            .executeAsOne()
                            .type shouldBe "registration_approval"
                    }
                    sql.notificationsQueries.countLiveForUser("plain-member").executeAsOne() shouldBe 0L
                    sql.notificationsQueries.countLiveForUser("ex-admin").executeAsOne() shouldBe 0L
                }
            }
        }

        // The counter-case that stops the test above being trivially satisfiable: an OPEN instance
        // creates an ACTIVE user with nothing to approve, so waking every admin would be noise.
        test("an OPEN instance notifies nobody — there is no decision to make") {
            withSqlDatabase {
                val notifier = RecordingPushNotifier()
                val svc = authService(sql, RegistrationPolicy.OPEN, clock, pepper, emitter(notifier))

                runTest {
                    val root = svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                    notifier.sent.clear()

                    svc.register(RegisterRequest("alice@x", "x".repeat(8), "Alice")).shouldSucceed()

                    notifier.sent.shouldBeEmpty()
                    sql.notificationsQueries.countLiveForUser(root.user.id.value).executeAsOne() shouldBe 0L
                }
            }
        }

        // ⛔ The property that matters most in production. Push is a wake-up accelerant; the
        // request itself lives in the synced admin roster. A relay outage, a dead token store, or
        // a thrown exception must cost the notification and nothing else — never the registration.
        // Without this, an unreachable relay would stop people signing up at all.
        test("a notifier that throws does not fail the registration") {
            withSqlDatabase {
                val svc = authService(sql, RegistrationPolicy.APPROVAL_QUEUE, clock, pepper, emitter(ThrowingPushNotifier))

                runTest {
                    val root = svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()

                    val out = svc.register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))

                    out.shouldSucceed().shouldBeInstanceOf<RegisterResult.PendingApproval>()
                    // The inbox row is minted before the push attempt, so the durable record
                    // survives the relay outage that ate the wake-up.
                    sql.notificationsQueries.countLiveForUser(root.user.id.value).executeAsOne() shouldBe 1L
                }
            }
        }

        // ⛔ The production path, which every test above skipped. No registration is ever served
        // by the singleton these tests construct: the public RPC mount rebinds it per call —
        // `authService.withRemoteHost(remoteHost)` — so the instance that actually handles a
        // signup is a copy. The copy is hand-written, and it silently dropped `notifications`,
        // a nullable-with-default parameter. Every test above passed while production notified
        // no admin at all, because they all called the original. This one registers through the
        // same rebinding the mount does, so a dropped collaborator fails here instead of in
        // someone's live server.
        test("an admin is still notified when registration is served through a rebound copy") {
            withSqlDatabase {
                val notifier = RecordingPushNotifier()
                val svc = authService(sql, RegistrationPolicy.APPROVAL_QUEUE, clock, pepper, emitter(notifier))

                runTest {
                    val root = svc.setupRoot(RegisterRequest("root@x", "x".repeat(8), "Root")).shouldSucceed()
                    notifier.sent.clear()

                    // Exactly what RpcRoutes does before serving the public mount, plus the REST
                    // path's User-Agent binding and the authed mount's principal binding — a
                    // collaborator must survive all three.
                    val served =
                        svc
                            .withRemoteHost("203.0.113.7")
                            .withUserAgent("ListenUp/1.0")
                            .copyWith(PrincipalProvider.None)

                    val pending =
                        served
                            .register(RegisterRequest("alice@x", "x".repeat(8), "Alice"))
                            .shouldSucceed()
                            .shouldBeInstanceOf<RegisterResult.PendingApproval>()

                    notifier.sent.map { it.first } shouldContainExactlyInAnyOrder listOf(root.user.id.value)
                    notifier.sent.forEach { (_, payload) ->
                        payload.shouldBeInstanceOf<PushPayload.RegistrationApproval>().userId shouldBe
                            pending.userId.value
                    }
                    sql.notificationsQueries.countLiveForUser(root.user.id.value).executeAsOne() shouldBe 1L
                }
            }
        }

        test("no notifier bound at all is simply quiet, not broken") {
            withSqlDatabase {
                // Forks without a relay assemble the auth module with no push module at all.
                val svc = authService(sql, RegistrationPolicy.APPROVAL_QUEUE, clock, pepper, notifications = null)

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

/** A real [NotificationEmitter] over the test database, delivering pushes to [notifier]. */
private fun SqlTestDatabases.emitter(notifier: PushNotifier): NotificationEmitter =
    NotificationEmitter(
        db = sql,
        repo = notificationFixture(),
        prefs = NotificationPrefsRepository(sql),
        notifier = notifier,
    )

@OptIn(ExperimentalTime::class)
private fun authService(
    db: ListenUpDatabase,
    policy: RegistrationPolicy,
    clock: FixedClock,
    pepper: ByteArray,
    notifications: NotificationEmitter?,
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
        notifications = notifications,
    )
}

/** Asserts the [AppResult] is a Success and returns the unwrapped value. */
private fun <T> AppResult<T>.shouldSucceed(): T = shouldBeInstanceOf<AppResult.Success<T>>().data
