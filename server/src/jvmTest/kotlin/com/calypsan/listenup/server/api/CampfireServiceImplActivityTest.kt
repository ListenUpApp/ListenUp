@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.api.push.PushPayload
import com.calypsan.listenup.server.campfire.CampfireRegistry
import com.calypsan.listenup.server.services.ActivityRepository
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Invite (push-notification + listInvitableUsers) and "listened together" activity-recording tests
 * for [CampfireServiceImpl], over the same real-database + real-registry rig as
 * [CampfireServiceImplAccessTest].
 *
 * Split from a single `CampfireServiceImplTest` (LargeClass) — book-access/host-only/spoiler/
 * observeSession/discovery tests live in [CampfireServiceImplAccessTest]; shared fixtures
 * (`makeService`, `principalFor`, `makeBookAccessible`, ...) live in
 * `CampfireServiceImplTestFixtures.kt`.
 */
class CampfireServiceImplActivityTest :
    FunSpec({

        // ── Invites: push notification (Task 5) ────────────────────────────────

        test(
            "createSession push-notifies an invited user who can access the book, " +
                "but never an inaccessible invitee or the inviter",
        ) {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestUser("member")
                sql.seedTestUser("stranger")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    makeBookAccessible(sql, driver, "book-a", "member")
                    // "stranger" is deliberately never given access to "book-a".
                    val notifier = RecordingPushNotifier()
                    val inviteSettings =
                        CampfireSettings(
                            name = "Test Campfire",
                            controlMode = CampfireControlMode.EVERYONE,
                            inviteOnly = false,
                            invitedUserIds = listOf("member", "stranger", "host"),
                        )

                    val created =
                        makeService(
                            sql,
                            driver,
                            CampfireRegistry(clock = FixedClock(campfireTestT0)),
                            principalFor("host"),
                            pushNotifier = notifier,
                        ).createSession("book-a", inviteSettings).value()

                    notifier.calls.map { it.first } shouldBe listOf("member")
                    val payload =
                        notifier.calls
                            .single()
                            .second
                            .shouldBeInstanceOf<PushPayload.CampfireInvite>()
                    payload.campfireId shouldBe created.id.value
                    payload.bookId shouldBe "book-a"
                    payload.inviterUserId shouldBe "host"
                }
            }
        }

        test("createSession with no invited users sends no push") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    val notifier = RecordingPushNotifier()

                    makeService(
                        sql,
                        driver,
                        CampfireRegistry(clock = FixedClock(campfireTestT0)),
                        principalFor("host"),
                        pushNotifier = notifier,
                    ).createSession("book-a", campfireEveryoneSettings).value()

                    notifier.calls.shouldBeEmpty()
                }
            }
        }

        // ── Invites: listInvitableUsers (Task 5) ───────────────────────────────

        test("listInvitableUsers returns only active users who can access the book, excluding the caller") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestUser("member")
                sql.seedTestUser("stranger")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    makeBookAccessible(sql, driver, "book-a", "member")
                    // "stranger" is deliberately never given access to "book-a".

                    val invitable =
                        makeService(sql, driver, CampfireRegistry(clock = FixedClock(campfireTestT0)), principalFor("host"))
                            .listInvitableUsers("book-a")
                            .value()

                    invitable.map { it.userId } shouldBe listOf("member")
                }
            }
        }

        // ── "Listened together" activity (Task 5) ──────────────────────────────

        test("endSession by the host records CAMPFIRE_TOGETHER when at least 2 users ever joined") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestUser("member")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    makeBookAccessible(sql, driver, "book-a", "member")
                    val registry = CampfireRegistry(clock = FixedClock(campfireTestT0))
                    val hostService = makeService(sql, driver, registry, principalFor("host"))
                    val created = hostService.createSession("book-a", campfireEveryoneSettings).value()
                    makeService(sql, driver, registry, principalFor("member")).joinSession(created.id).value()

                    hostService.endSession(created.id).value()

                    val rows = ActivityRepository(db = sql).page(before = null, limit = 10)
                    rows shouldHaveSize 1
                    rows.single().type shouldBe ActivityType.CAMPFIRE_TOGETHER
                    rows.single().userId shouldBe "host"
                    rows.single().bookId shouldBe "book-a"
                }
            }
        }

        test("endSession by a solo host does NOT record a listened-together activity") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    val hostService =
                        makeService(sql, driver, CampfireRegistry(clock = FixedClock(campfireTestT0)), principalFor("host"))
                    val created = hostService.createSession("book-a", campfireEveryoneSettings).value()

                    hostService.endSession(created.id).value()

                    ActivityRepository(db = sql).page(before = null, limit = 10).shouldBeEmpty()
                }
            }
        }

        test("leaveSession by the last member ending a >=2-participant room records CAMPFIRE_TOGETHER") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestUser("member")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    makeBookAccessible(sql, driver, "book-a", "member")
                    val registry = CampfireRegistry(clock = FixedClock(campfireTestT0))
                    val hostService = makeService(sql, driver, registry, principalFor("host"))
                    val created = hostService.createSession("book-a", campfireEveryoneSettings).value()
                    val memberService = makeService(sql, driver, registry, principalFor("member"))
                    memberService.joinSession(created.id).value()
                    memberService.leaveSession(created.id).value() // room continues — host still present

                    hostService.leaveSession(created.id).value() // now empties and ends the room

                    val rows = ActivityRepository(db = sql).page(before = null, limit = 10)
                    rows shouldHaveSize 1
                    rows.single().type shouldBe ActivityType.CAMPFIRE_TOGETHER
                }
            }
        }

        test("leaveSession by the last member of a solo room does NOT record a listened-together activity") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    val hostService =
                        makeService(sql, driver, CampfireRegistry(clock = FixedClock(campfireTestT0)), principalFor("host"))
                    val created = hostService.createSession("book-a", campfireEveryoneSettings).value()

                    hostService.leaveSession(created.id).value()

                    ActivityRepository(db = sql).page(before = null, limit = 10).shouldBeEmpty()
                }
            }
        }
    })
