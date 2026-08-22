@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.notifications

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.notifications.NotificationEvent
import com.calypsan.listenup.api.notifications.NotificationPreference
import com.calypsan.listenup.api.push.PushPayload
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.push.PushNotifier
import com.calypsan.listenup.server.push.PushWatchKind
import com.calypsan.listenup.server.sync.notificationFixture
import com.calypsan.listenup.server.testing.MutableClock
import com.calypsan.listenup.server.testing.SqlTestDatabases
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Behaviour of [NotificationEmitter] — the one seam call sites use to notify users. Covers the
 * preference matrix (default / fully muted / push-muted / in-app-muted), the Admins audience
 * fan-out, retention pruning, and the never-throws contract when the [PushNotifier] misbehaves.
 */
class NotificationEmitterTest :
    FunSpec({

        fun SqlTestDatabases.emitterFixture(
            notifier: PushNotifier,
            clock: Clock = Clock.System,
        ): Pair<NotificationEmitter, NotificationPrefsRepository> {
            val prefs = NotificationPrefsRepository(sql)
            val emitter =
                NotificationEmitter(
                    db = sql,
                    repo = notificationFixture(clock = clock),
                    prefs = prefs,
                    notifier = notifier,
                )
            return emitter to prefs
        }

        test("default preferences mint a row and send a push") {
            withSqlDatabase {
                val notifier = RecordingPushNotifier()
                val (emitter, _) = emitterFixture(notifier)
                runTest {
                    val event = NotificationEvent.RegistrationApproval(userId = "u-p")

                    emitter.emit(event, NotificationAudience.User("admin-1"))

                    sql.notificationsQueries.countLiveForUser("admin-1").executeAsOne() shouldBe 1L
                    val rowId =
                        sql.notificationsQueries
                            .selectLiveIdsForUserOldestFirst("admin-1", 1L)
                            .executeAsOne()
                    val row = sql.notificationsQueries.selectById(rowId).executeAsOne()
                    row.type shouldBe "registration_approval"
                    row.user_id shouldBe "admin-1"
                    contractJson.decodeFromString(NotificationEvent.serializer(), row.payload) shouldBe event
                    notifier.sent shouldBe listOf("admin-1" to PushPayload.RegistrationApproval(userId = "u-p"))
                }
            }
        }

        test("a fully muted type mints no row and sends no push") {
            withSqlDatabase {
                val notifier = RecordingPushNotifier()
                val (emitter, prefs) = emitterFixture(notifier)
                runTest {
                    prefs.update(
                        userId = "admin-1",
                        type = "registration_approval",
                        preference = NotificationPreference(inApp = false, push = false),
                    ) shouldBe true

                    emitter.emit(
                        NotificationEvent.RegistrationApproval(userId = "u-p"),
                        NotificationAudience.User("admin-1"),
                    )

                    sql.notificationsQueries.countLiveForUser("admin-1").executeAsOne() shouldBe 0L
                    notifier.sent.shouldBeEmpty()
                }
            }
        }

        test("push-muted but in-app-enabled mints a row and sends no push") {
            withSqlDatabase {
                val notifier = RecordingPushNotifier()
                val (emitter, prefs) = emitterFixture(notifier)
                runTest {
                    prefs.update(
                        userId = "admin-1",
                        type = "registration_approval",
                        preference = NotificationPreference(inApp = true, push = false),
                    ) shouldBe true

                    emitter.emit(
                        NotificationEvent.RegistrationApproval(userId = "u-p"),
                        NotificationAudience.User("admin-1"),
                    )

                    sql.notificationsQueries.countLiveForUser("admin-1").executeAsOne() shouldBe 1L
                    notifier.sent.shouldBeEmpty()
                }
            }
        }

        test("in-app-muted but push-enabled sends the push and mints no row") {
            withSqlDatabase {
                val notifier = RecordingPushNotifier()
                val (emitter, prefs) = emitterFixture(notifier)
                runTest {
                    prefs.update(
                        userId = "admin-1",
                        type = "registration_approval",
                        preference = NotificationPreference(inApp = false, push = true),
                    ) shouldBe true

                    emitter.emit(
                        NotificationEvent.RegistrationApproval(userId = "u-p"),
                        NotificationAudience.User("admin-1"),
                    )

                    sql.notificationsQueries.countLiveForUser("admin-1").executeAsOne() shouldBe 0L
                    notifier.sent shouldBe listOf("admin-1" to PushPayload.RegistrationApproval(userId = "u-p"))
                }
            }
        }

        test("the Admins audience fans out to every current admin, and only admins") {
            withSqlDatabase {
                val notifier = RecordingPushNotifier()
                val (emitter, _) = emitterFixture(notifier)
                sql.seedTestUser("root-1", userRole = UserRoleColumn.ROOT)
                sql.seedTestUser("admin-1", userRole = UserRoleColumn.ADMIN)
                sql.seedTestUser("member-1", userRole = UserRoleColumn.MEMBER)
                runTest {
                    emitter.emit(
                        NotificationEvent.RegistrationApproval(userId = "u-p"),
                        NotificationAudience.Admins,
                    )

                    sql.notificationsQueries.countLiveForUser("root-1").executeAsOne() shouldBe 1L
                    sql.notificationsQueries.countLiveForUser("admin-1").executeAsOne() shouldBe 1L
                    sql.notificationsQueries.countLiveForUser("member-1").executeAsOne() shouldBe 0L
                    notifier.sent.map { it.first }.toSet() shouldBe setOf("root-1", "admin-1")
                }
            }
        }

        test("retention prunes the oldest rows beyond the ceiling") {
            withSqlDatabase {
                val clock = MutableClock(Instant.fromEpochMilliseconds(1_000_000L))
                val notifier = RecordingPushNotifier()
                val (emitter, _) = emitterFixture(notifier, clock = clock)
                runTest {
                    emitter.emit(
                        NotificationEvent.RegistrationApproval(userId = "u-0"),
                        NotificationAudience.User("u-1"),
                    )
                    val firstRowId =
                        sql.notificationsQueries
                            .selectLiveIdsForUserOldestFirst("u-1", 1L)
                            .executeAsOne()
                    repeat(NotificationEmitter.RETENTION_PER_USER) { i ->
                        clock.instant += 1.seconds
                        emitter.emit(
                            NotificationEvent.RegistrationApproval(userId = "u-$i"),
                            NotificationAudience.User("u-1"),
                        )
                    }

                    sql.notificationsQueries.countLiveForUser("u-1").executeAsOne() shouldBe
                        NotificationEmitter.RETENTION_PER_USER.toLong()
                    // The FIRST-minted row is the tombstoned one — oldest-first, not arbitrary.
                    sql.notificationsQueries
                        .selectById(firstRowId)
                        .executeAsOne()
                        .deleted_at shouldNotBe null
                }
            }
        }

        test("a notifier that throws does not fail the emit — the rows still land") {
            withSqlDatabase {
                val (emitter, _) = emitterFixture(ThrowingPushNotifier())
                runTest {
                    // A 2-user audience: each user is isolated, so the first user's push failure
                    // must not abort the second user's row either.
                    emitter.emit(
                        NotificationEvent.RegistrationApproval(userId = "u-p"),
                        NotificationAudience.Users(listOf("adm-1", "adm-2")),
                    )

                    sql.notificationsQueries.countLiveForUser("adm-1").executeAsOne() shouldBe 1L
                    sql.notificationsQueries.countLiveForUser("adm-2").executeAsOne() shouldBe 1L
                    sql.notificationsQueries
                        .selectLiveIdsForUserOldestFirst("adm-1", 1L)
                        .executeAsOneOrNull()
                        .shouldNotBeNull()
                }
            }
        }
    })

/** Records every [notify] call so tests can assert exactly what was pushed, to whom, in order. */
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

/** A [PushNotifier] that violates its never-throw contract, to prove the emitter contains it. */
private class ThrowingPushNotifier : PushNotifier {
    override suspend fun notify(
        userId: String,
        payload: PushPayload,
    ): Unit = throw IllegalStateException("relay exploded")

    override suspend fun notifyWatch(
        kind: PushWatchKind,
        key: String,
        payload: PushPayload,
    ) = Unit
}
