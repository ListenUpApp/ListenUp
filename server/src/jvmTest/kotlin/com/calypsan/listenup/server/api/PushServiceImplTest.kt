@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.RegistrationPolicy
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.PushError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.push.PushPayload
import com.calypsan.listenup.api.push.PushPlatform
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.push.PushConfig
import com.calypsan.listenup.server.push.PushNotifier
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant

/**
 * Contract tests for [PushServiceImpl] — the session-bound push-token registry.
 *
 * Uses a real in-memory-migrated SQLite database (via [withSqlDatabase]); no mocks. A
 * [RecordingPushNotifier] fake captures the (userId, payload) pair [PushServiceImpl.sendTestNotification]
 * routes through [PushNotifier].
 */
class PushServiceImplTest :
    FunSpec({

        val fixedNow = Instant.fromEpochMilliseconds(1_730_000_000_000L)

        fun principalFor(
            userId: String,
            sessionId: String,
            role: UserRole = UserRole.MEMBER,
        ): PrincipalProvider = PrincipalProvider { UserPrincipal(UserId(userId), SessionId(sessionId), role) }

        fun noPrincipal(): PrincipalProvider = PrincipalProvider { null }

        /** Inserts a live (unrevoked, unexpired) `sessions` row so `push_tokens.session_id` FK-resolves. */
        fun ListenUpDatabase.seedTestSession(
            sessionId: String,
            userId: String,
        ) {
            sessionsQueries.insert(
                id = sessionId,
                user_id = userId,
                refresh_token_hash = "hash-$sessionId",
                family_id = "family-$sessionId",
                previous_hash = null,
                label = null,
                device_type = null,
                platform = null,
                platform_version = null,
                client_name = null,
                client_version = null,
                device_name = null,
                device_model = null,
                user_agent = null,
                created_at = 1L,
                expires_at = fixedNow.toEpochMilliseconds() + 1_000_000L,
                last_used_at = 1L,
                revoked_at = null,
            )
        }

        fun makeService(
            sql: ListenUpDatabase,
            principal: PrincipalProvider,
            notifier: PushNotifier = RecordingPushNotifier(),
            pushConfig: PushConfig = PushConfig(relayUrl = "https://push.example.com"),
            settings: ServerSettingsRepository = ServerSettingsRepository(sql = sql, default = RegistrationPolicy.APPROVAL_QUEUE),
        ): PushServiceImpl =
            PushServiceImpl(
                db = sql,
                pushConfig = pushConfig,
                settings = settings,
                notifier = notifier,
                clock = FixedClock(fixedNow),
                principal = principal,
            )

        fun <T> AppResult<T>.value(): T {
            this.shouldBeInstanceOf<AppResult.Success<T>>()
            return data
        }

        test("registerToken upserts bound to the calling session") {
            withSqlDatabase {
                sql.seedTestUser("alice")
                sql.seedTestSession("session-1", "alice")
                sql.seedTestSession("session-2", "alice")
                runTest {
                    val service1 = makeService(sql, principalFor("alice", "session-1"))
                    service1.registerToken("token-abc", PushPlatform.ANDROID).value()

                    val row = sql.pushTokensQueries.countAll().executeAsOne()
                    row shouldBe 1L

                    // Re-registering the SAME token from a second session re-binds it, not duplicates it.
                    val service2 = makeService(sql, principalFor("alice", "session-2"))
                    service2.registerToken("token-abc", PushPlatform.ANDROID).value()

                    sql.pushTokensQueries.countAll().executeAsOne() shouldBe 1L
                    val live =
                        sql.pushTokensQueries
                            .selectLiveForUser(user_id = "alice", now = fixedNow.toEpochMilliseconds())
                            .executeAsList()
                    live shouldHaveSize 1
                    live.first().token shouldBe "token-abc"
                }
            }
        }

        test("registerToken validates token shape") {
            withSqlDatabase {
                sql.seedTestUser("alice")
                sql.seedTestSession("session-1", "alice")
                runTest {
                    val service = makeService(sql, principalFor("alice", "session-1"))

                    val blank = service.registerToken("   ", PushPlatform.ANDROID)
                    blank.shouldBeInstanceOf<AppResult.Failure>()
                    blank.error.shouldBeInstanceOf<ValidationError>()

                    val tooLong = service.registerToken("a".repeat(4097), PushPlatform.ANDROID)
                    tooLong.shouldBeInstanceOf<AppResult.Failure>()
                    tooLong.error.shouldBeInstanceOf<ValidationError>()

                    sql.pushTokensQueries.countAll().executeAsOne() shouldBe 0L
                }
            }
        }

        test("registerToken fails PUSH_DISABLED when toggle off or relay unconfigured") {
            withSqlDatabase {
                sql.seedTestUser("alice")
                sql.seedTestSession("session-1", "alice")
                runTest {
                    // Axis 1: admin toggle off, relay configured.
                    val settings = ServerSettingsRepository(sql = sql, default = RegistrationPolicy.APPROVAL_QUEUE)
                    settings.setPushNotificationsEnabled(false)
                    val toggleOff =
                        makeService(sql, principalFor("alice", "session-1"), settings = settings)
                            .registerToken("token-1", PushPlatform.ANDROID)
                    toggleOff.shouldBeInstanceOf<AppResult.Failure>()
                    toggleOff.error.shouldBeInstanceOf<PushError.PushDisabled>()

                    // Axis 2: admin toggle on (default), relay unconfigured.
                    val noRelay =
                        makeService(sql, principalFor("alice", "session-1"), pushConfig = PushConfig(relayUrl = null))
                            .registerToken("token-2", PushPlatform.ANDROID)
                    noRelay.shouldBeInstanceOf<AppResult.Failure>()
                    noRelay.error.shouldBeInstanceOf<PushError.PushDisabled>()

                    sql.pushTokensQueries.countAll().executeAsOne() shouldBe 0L
                }
            }
        }

        test("unregisterToken deletes only the caller's token") {
            withSqlDatabase {
                sql.seedTestUser("alice")
                sql.seedTestUser("bob")
                sql.seedTestSession("alice-session", "alice")
                sql.seedTestSession("bob-session", "bob")
                runTest {
                    makeService(sql, principalFor("alice", "alice-session"))
                        .registerToken("shared-prefix-alice", PushPlatform.ANDROID)
                        .value()
                    makeService(sql, principalFor("bob", "bob-session"))
                        .registerToken("shared-prefix-bob", PushPlatform.IOS)
                        .value()

                    makeService(sql, principalFor("alice", "alice-session"))
                        .unregisterToken("shared-prefix-alice")
                        .value()

                    sql.pushTokensQueries.countAll().executeAsOne() shouldBe 1L
                    val remaining =
                        sql.pushTokensQueries
                            .selectLiveForUser(user_id = "bob", now = fixedNow.toEpochMilliseconds())
                            .executeAsList()
                    remaining shouldHaveSize 1
                    remaining.first().token shouldBe "shared-prefix-bob"
                }
            }
        }

        test("sendTestNotification routes a TestNotification through PushNotifier") {
            withSqlDatabase {
                sql.seedTestUser("alice")
                sql.seedTestSession("session-1", "alice")
                runTest {
                    val notifier = RecordingPushNotifier()
                    makeService(sql, principalFor("alice", "session-1"), notifier = notifier)
                        .sendTestNotification()
                        .value()

                    notifier.calls shouldHaveSize 1
                    val (userId, payload) = notifier.calls.first()
                    userId shouldBe "alice"
                    payload.shouldBeInstanceOf<PushPayload.TestNotification>()
                    payload.sentAtMs shouldBe fixedNow.toEpochMilliseconds()
                }
            }
        }

        test("methods fail with AuthError.PermissionDenied when caller is unauthenticated") {
            withSqlDatabase {
                runTest {
                    val service = makeService(sql, noPrincipal())

                    val register = service.registerToken("token", PushPlatform.ANDROID)
                    register.shouldBeInstanceOf<AppResult.Failure>()
                    register.error.shouldBeInstanceOf<AuthError.PermissionDenied>()

                    val unregister = service.unregisterToken("token")
                    unregister.shouldBeInstanceOf<AppResult.Failure>()
                    unregister.error.shouldBeInstanceOf<AuthError.PermissionDenied>()

                    val test = service.sendTestNotification()
                    test.shouldBeInstanceOf<AppResult.Failure>()
                    test.error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                }
            }
        }
    })

/** Records every `(userId, payload)` pair passed to [notify] — the test double for [PushNotifier]. */
class RecordingPushNotifier : PushNotifier {
    val calls = mutableListOf<Pair<String, PushPayload>>()

    override suspend fun notify(
        userId: String,
        payload: PushPayload,
    ) {
        calls += userId to payload
    }
}
