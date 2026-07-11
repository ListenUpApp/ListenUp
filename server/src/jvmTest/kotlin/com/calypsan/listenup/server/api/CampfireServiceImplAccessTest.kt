@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireFrame
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.api.dto.campfire.PlaybackCommand
import com.calypsan.listenup.api.error.CampfireError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.server.campfire.CampfireRegistry
import com.calypsan.listenup.server.sync.ChangeBus
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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes

/**
 * Access + control gating tests for [CampfireServiceImpl] over a real, migrated in-memory
 * database (via [withSqlDatabase]) and a real [CampfireRegistry] — the [PushServiceImplTest]
 * pattern. Anchor math and pure room-state behavior are [CampfireRegistry]'s own tests
 * ([com.calypsan.listenup.server.campfire.CampfireRegistryTest]); these tests exercise the
 * layer above it: book-access gating, host-only enforcement, the §7 spoiler check, the
 * observeSession presence contract, and open-session discovery.
 *
 * Split from a single `CampfireServiceImplTest` (LargeClass) — invite/push-notification and
 * "listened together" activity tests live in [CampfireServiceImplActivityTest]; shared fixtures
 * (`makeService`, `principalFor`, `makeBookAccessible`, `seedPosition`/`seedChapter`, ...) live in
 * `CampfireServiceImplTestFixtures.kt`.
 */
class CampfireServiceImplAccessTest :
    FunSpec({

        // ── Access gating ─────────────────────────────────────────────────────

        test("createSession fails BookAccessDenied when the caller lacks book access") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                sql.seedTestBook("book-a")
                runTest {
                    val service = makeService(sql, driver, CampfireRegistry(clock = FixedClock(campfireTestT0)), principalFor("member"))

                    val result = service.createSession("book-a", campfireEveryoneSettings)

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<CampfireError.BookAccessDenied>()
                }
            }
        }

        test("joinSession fails CampfireNotFound when the caller lacks book access") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestUser("stranger")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    val registry = CampfireRegistry(clock = FixedClock(campfireTestT0))
                    val created =
                        makeService(sql, driver, registry, principalFor("host")).createSession("book-a", campfireEveryoneSettings).value()

                    val result = makeService(sql, driver, registry, principalFor("stranger")).joinSession(created.id)

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<CampfireError.CampfireNotFound>()
                }
            }
        }

        test("createSession succeeds with the creator's stored position as the starting anchor, never a spoiler") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestBook("book-a")
                sql.seedPosition("host", "book-a", positionMs = 123_000L)
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")

                    val snapshot =
                        makeService(sql, driver, CampfireRegistry(clock = FixedClock(campfireTestT0)), principalFor("host"))
                            .createSession("book-a", campfireEveryoneSettings)
                            .value()

                    snapshot.anchor.positionMs shouldBe 123_000L
                    snapshot.anchor.isPlaying shouldBe false
                    snapshot.yourPositionMs shouldBe 123_000L
                    snapshot.spoilerAhead shouldBe false
                }
            }
        }

        // ── Spoiler-ahead (§7) ────────────────────────────────────────────────

        test("joinSession flags spoilerAhead when the room is more than 10 minutes past the caller's position") {
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
                    // Rooms are born in LOBBY — go LIVE before issuing playback commands.
                    hostService.startSession(created.id).value()
                    // Room jumps 700s ahead of position 0 — past the 600s (10-minute) threshold.
                    hostService.sendCommand(created.id, PlaybackCommand.SeekTo(positionMs = 700_000L, commandId = "c1")).value()

                    val joined = makeService(sql, driver, registry, principalFor("member")).joinSession(created.id).value()

                    joined.spoilerAhead shouldBe true
                }
            }
        }

        test("joinSession flags spoilerAhead when the room is more than one chapter past the caller's position") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestUser("member")
                sql.seedTestBook("book-a")
                sql.seedChapter("book-a", ordinal = 0, startTimeMs = 0L)
                sql.seedChapter("book-a", ordinal = 1, startTimeMs = 200_000L)
                sql.seedChapter("book-a", ordinal = 2, startTimeMs = 400_000L)
                sql.seedChapter("book-a", ordinal = 3, startTimeMs = 600_000L)
                sql.seedPosition("member", "book-a", positionMs = 50_000L) // chapter 0
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    makeBookAccessible(sql, driver, "book-a", "member")
                    val registry = CampfireRegistry(clock = FixedClock(campfireTestT0))
                    val hostService = makeService(sql, driver, registry, principalFor("host"))
                    val created = hostService.createSession("book-a", campfireEveryoneSettings).value()
                    // Rooms are born in LOBBY — go LIVE before issuing playback commands.
                    hostService.startSession(created.id).value()
                    // 620s → chapter 3; within the 10-minute time threshold of 50s, but 3 chapters ahead.
                    hostService.sendCommand(created.id, PlaybackCommand.SeekTo(positionMs = 620_000L, commandId = "c1")).value()

                    val joined = makeService(sql, driver, registry, principalFor("member")).joinSession(created.id).value()

                    joined.spoilerAhead shouldBe true
                }
            }
        }

        test("joinSession does NOT flag spoilerAhead when the room is close to the caller's position") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestUser("member")
                sql.seedTestBook("book-a")
                sql.seedPosition("member", "book-a", positionMs = 0L)
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    makeBookAccessible(sql, driver, "book-a", "member")
                    val registry = CampfireRegistry(clock = FixedClock(campfireTestT0))
                    val hostService = makeService(sql, driver, registry, principalFor("host"))
                    val created = hostService.createSession("book-a", campfireEveryoneSettings).value()
                    // Rooms are born in LOBBY — go LIVE before issuing playback commands.
                    hostService.startSession(created.id).value()
                    hostService.sendCommand(created.id, PlaybackCommand.SeekTo(positionMs = 200_000L, commandId = "c1")).value()

                    val joined = makeService(sql, driver, registry, principalFor("member")).joinSession(created.id).value()

                    joined.spoilerAhead shouldBe false
                }
            }
        }

        // ── Host-only control gating ──────────────────────────────────────────

        test("transferHost by a non-host member fails NotController") {
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

                    val result = memberService.transferHost(created.id, "member")

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<CampfireError.NotController>()
                }
            }
        }

        test("endSession by a non-host member fails NotController") {
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

                    val result = memberService.endSession(created.id)

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<CampfireError.NotController>()
                }
            }
        }

        test("setControlMode by a non-host member fails NotController") {
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

                    val result = memberService.setControlMode(created.id, CampfireControlMode.HOST_ONLY)

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<CampfireError.NotController>()
                }
            }
        }

        test("sendCommand in HOST_ONLY mode by a non-host member fails NotController") {
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
                    val created = hostService.createSession("book-a", campfireHostOnlySettings).value()
                    val memberService = makeService(sql, driver, registry, principalFor("member"))
                    memberService.joinSession(created.id).value()

                    val result = memberService.sendCommand(created.id, PlaybackCommand.Play(commandId = "c1"))

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<CampfireError.NotController>()
                }
            }
        }

        // ── observeSession presence ────────────────────────────────────────────

        test("observeSession as a non-member emits a single NotAMember error frame") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestUser("stranger")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    val registry = CampfireRegistry(clock = FixedClock(campfireTestT0))
                    val created =
                        makeService(sql, driver, registry, principalFor("host")).createSession("book-a", campfireEveryoneSettings).value()

                    val events = makeService(sql, driver, registry, principalFor("stranger")).observeSession(created.id).toList()

                    events shouldHaveSize 1
                    (events.first() as RpcEvent.Error).error.shouldBeInstanceOf<CampfireError.NotAMember>()
                }
            }
        }

        test("observeSession collector cancel marks the member away; reaping past grace evicts with MemberLeft") {
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

                    // Subscribe as the host FIRST so the replay-0 SharedFlow doesn't drop the MemberLeft frame.
                    val observedFrames = mutableListOf<CampfireFrame>()
                    val observerJob = launch { registry.observe(created.id)!!.collect { observedFrames += it } }
                    advanceUntilIdle()

                    val memberJob = launch { memberService.observeSession(created.id).collect { } }
                    advanceUntilIdle()
                    memberJob.cancel()
                    advanceUntilIdle()

                    registry
                        .snapshot(created.id)!!
                        .members
                        .first { it.userId == "member" }
                        .isAway shouldBe true

                    registry.reapAwayMembers(now = campfireTestT0 + 3.minutes)
                    advanceUntilIdle()

                    observedFrames.filterIsInstance<CampfireFrame.MemberLeft>().any { it.member.userId == "member" } shouldBe true
                    observerJob.cancel()
                }
            }
        }

        // ── Discovery: ACL-filtered listOpenSessions (Task 4) ─────────────────

        test("listOpenSessions returns only rooms whose book is accessible to the caller") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestUser("member")
                sql.seedTestBook("book-a")
                sql.seedTestBook("book-b")
                runTest {
                    // "member" can see book-a but not book-b.
                    makeBookAccessible(sql, driver, "book-a", "host")
                    makeBookAccessible(sql, driver, "book-a", "member")
                    makeBookAccessible(sql, driver, "book-b", "host", allBooksId = "all-books-b")
                    val registry = CampfireRegistry(clock = FixedClock(campfireTestT0))
                    val hostService = makeService(sql, driver, registry, principalFor("host"))
                    hostService.createSession("book-a", campfireEveryoneSettings).value()
                    hostService.createSession("book-b", campfireEveryoneSettings).value()

                    val visible = makeService(sql, driver, registry, principalFor("member")).listOpenSessions().value()

                    visible.map { it.bookId } shouldBe listOf("book-a")
                }
            }
        }

        test("listOpenSessions excludes an invite-only room from a stranger, but shows it to the invited and to members") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestUser("member")
                sql.seedTestUser("invited")
                sql.seedTestUser("stranger")
                sql.seedTestBook("book-a")
                runTest {
                    listOf("host", "member", "invited", "stranger").forEach { makeBookAccessible(sql, driver, "book-a", it) }
                    val registry = CampfireRegistry(clock = FixedClock(campfireTestT0))
                    val inviteOnlySettings =
                        CampfireSettings(
                            name = "Test Campfire",
                            controlMode = CampfireControlMode.EVERYONE,
                            inviteOnly = true,
                            invitedUserIds = listOf("invited"),
                        )
                    val hostService = makeService(sql, driver, registry, principalFor("host"))
                    val created = hostService.createSession("book-a", inviteOnlySettings).value()
                    makeService(sql, driver, registry, principalFor("member")).joinSession(created.id).value()

                    makeService(sql, driver, registry, principalFor("stranger")).listOpenSessions().value().shouldBeEmpty()
                    makeService(sql, driver, registry, principalFor("invited")).listOpenSessions().value() shouldHaveSize 1
                    makeService(sql, driver, registry, principalFor("member")).listOpenSessions().value() shouldHaveSize 1
                    makeService(sql, driver, registry, principalFor("host")).listOpenSessions().value() shouldHaveSize 1
                }
            }
        }

        // ── Discovery: CampfiresChanged nudge (Task 4) ────────────────────────

        test("createSession broadcasts CampfiresChanged") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    val bus = ChangeBus()
                    val sub = async { bus.subscribeControl().first().control }
                    advanceUntilIdle()

                    makeService(sql, driver, CampfireRegistry(clock = FixedClock(campfireTestT0)), principalFor("host"), bus = bus)
                        .createSession("book-a", campfireEveryoneSettings)
                        .value()

                    sub.await() shouldBe SyncControl.CampfiresChanged
                }
            }
        }

        test("endSession broadcasts CampfiresChanged") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    val bus = ChangeBus()
                    val registry = CampfireRegistry(clock = FixedClock(campfireTestT0))
                    val hostService = makeService(sql, driver, registry, principalFor("host"), bus = bus)
                    val created = hostService.createSession("book-a", campfireEveryoneSettings).value()

                    val sub = async { bus.subscribeControl().first { it.control == SyncControl.CampfiresChanged }.control }
                    advanceUntilIdle()
                    hostService.endSession(created.id).value()

                    sub.await() shouldBe SyncControl.CampfiresChanged
                }
            }
        }

        test("leaveSession by the last member broadcasts CampfiresChanged") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    val bus = ChangeBus()
                    val registry = CampfireRegistry(clock = FixedClock(campfireTestT0))
                    val hostService = makeService(sql, driver, registry, principalFor("host"), bus = bus)
                    val created = hostService.createSession("book-a", campfireEveryoneSettings).value()

                    val sub = async { bus.subscribeControl().first { it.control == SyncControl.CampfiresChanged }.control }
                    advanceUntilIdle()
                    hostService.leaveSession(created.id).value()

                    sub.await() shouldBe SyncControl.CampfiresChanged
                }
            }
        }
    })
