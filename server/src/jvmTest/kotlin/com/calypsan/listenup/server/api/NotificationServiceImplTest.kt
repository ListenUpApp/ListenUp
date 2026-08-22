@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.notifications.NotificationPreference
import com.calypsan.listenup.api.notifications.NotificationTypes
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.notifications.NotificationPrefsRepository
import com.calypsan.listenup.server.sync.NotificationRepository
import com.calypsan.listenup.server.sync.notificationFixture
import com.calypsan.listenup.server.sync.notificationPayload
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

/**
 * Contract tests for [NotificationServiceImpl] — the caller-scoped RPC surface over the
 * `notifications` inbox and the per-type preference store.
 *
 * Uses a real in-memory-migrated SQLite database (via [withSqlDatabase]); no mocks. The security
 * invariant under test: every caller-influenced mutation routes through
 * [NotificationRepository.markRead] (the ONLY ownership gate), so another user's row and an
 * unauthenticated caller both fail closed.
 */
class NotificationServiceImplTest :
    FunSpec({

        val fixedNow = Instant.fromEpochMilliseconds(1_730_000_000_000L)

        fun principalFor(
            userId: String,
            role: UserRole = UserRole.MEMBER,
        ): PrincipalProvider = PrincipalProvider { UserPrincipal(UserId(userId), SessionId("session-$userId"), role) }

        fun noPrincipal(): PrincipalProvider = PrincipalProvider { null }

        fun makeService(
            repo: NotificationRepository,
            prefs: NotificationPrefsRepository,
            principal: PrincipalProvider,
        ): NotificationServiceImpl =
            NotificationServiceImpl(
                repo = repo,
                prefs = prefs,
                clock = FixedClock(fixedNow),
                principal = principal,
            )

        fun <T> AppResult<T>.value(): T {
            this.shouldBeInstanceOf<AppResult.Success<T>>()
            return data
        }

        test("markRead sets readAt on the caller's row and bumps its revision") {
            withSqlDatabase {
                val repo = notificationFixture()
                val prefs = NotificationPrefsRepository(sql)
                runTest {
                    repo.upsert(notificationPayload("n-1"), userId = "alice")
                    val before = sql.notificationsQueries.selectById("n-1").executeAsOne()

                    makeService(repo, prefs, principalFor("alice")).markRead("n-1").value()

                    val after = sql.notificationsQueries.selectById("n-1").executeAsOne()
                    after.read_at shouldBe fixedNow.toEpochMilliseconds()
                    after.revision shouldBeGreaterThan before.revision
                }
            }
        }

        test("markRead twice is idempotent — second call succeeds without another revision bump") {
            withSqlDatabase {
                val repo = notificationFixture()
                val prefs = NotificationPrefsRepository(sql)
                runTest {
                    repo.upsert(notificationPayload("n-1"), userId = "alice")
                    val service = makeService(repo, prefs, principalFor("alice"))

                    service.markRead("n-1").value()
                    val afterFirst = sql.notificationsQueries.selectById("n-1").executeAsOne()

                    service.markRead("n-1").value()

                    val afterSecond = sql.notificationsQueries.selectById("n-1").executeAsOne()
                    afterSecond.revision shouldBe afterFirst.revision
                    afterSecond.read_at shouldBe afterFirst.read_at
                }
            }
        }

        test("markRead on another user's row is NotFound — ownership fails closed") {
            withSqlDatabase {
                val repo = notificationFixture()
                val prefs = NotificationPrefsRepository(sql)
                runTest {
                    repo.upsert(notificationPayload("n-1"), userId = "alice")

                    val result = makeService(repo, prefs, principalFor("bob")).markRead("n-1")

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<SyncError.NotFound>()
                    sql.notificationsQueries
                        .selectById("n-1")
                        .executeAsOne()
                        .read_at shouldBe null
                }
            }
        }

        test("markRead with no principal fails closed with PermissionDenied") {
            withSqlDatabase {
                val repo = notificationFixture()
                val prefs = NotificationPrefsRepository(sql)
                runTest {
                    repo.upsert(notificationPayload("n-1"), userId = "alice")

                    val result = makeService(repo, prefs, noPrincipal()).markRead("n-1")

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<AuthError.PermissionDenied>()
                    sql.notificationsQueries
                        .selectById("n-1")
                        .executeAsOne()
                        .read_at shouldBe null
                }
            }
        }

        test("getPreferences returns one row per registered type with defaults when nothing stored") {
            withSqlDatabase {
                val repo = notificationFixture()
                val prefs = NotificationPrefsRepository(sql)
                runTest {
                    val result = makeService(repo, prefs, principalFor("alice")).getPreferences().value()

                    result shouldHaveSize NotificationTypes.all.size
                    val campfire = result.first { it.type == "campfire_invite" }
                    campfire.preference shouldBe NotificationTypes.CAMPFIRE_INVITE.defaultPreference
                    campfire.pushEligible shouldBe NotificationTypes.CAMPFIRE_INVITE.pushEligible
                }
            }
        }

        test("updatePreference persists and a following getPreferences reflects it") {
            withSqlDatabase {
                val repo = notificationFixture()
                val prefs = NotificationPrefsRepository(sql)
                runTest {
                    val service = makeService(repo, prefs, principalFor("alice"))
                    val muted = NotificationPreference(inApp = false, push = false)

                    service.updatePreference("campfire_invite", muted).value()

                    val stored = service.getPreferences().value().first { it.type == "campfire_invite" }
                    stored.preference shouldBe muted
                }
            }
        }

        test("updatePreference with an unknown type is NotFound") {
            withSqlDatabase {
                val repo = notificationFixture()
                val prefs = NotificationPrefsRepository(sql)
                runTest {
                    val result =
                        makeService(repo, prefs, principalFor("alice"))
                            .updatePreference("no_such_type", NotificationPreference(inApp = true, push = true))

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<SyncError.NotFound>()
                }
            }
        }
    })
