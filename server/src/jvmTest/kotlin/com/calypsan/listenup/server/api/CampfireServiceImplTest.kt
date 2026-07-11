@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import app.cash.sqldelight.db.SqlDriver
import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.dto.auth.SessionId
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireFrame
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.api.dto.campfire.PlaybackCommand
import com.calypsan.listenup.api.error.CampfireError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.api.sync.CollectionBookSyncPayload
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.api.sync.CollectionSyncPayload
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.campfire.CampfireRegistry
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.CollectionBookRepository
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import com.calypsan.listenup.server.sync.PublicProfileRepository
import com.calypsan.listenup.server.sync.SyncRegistry
import com.calypsan.listenup.server.testing.FixedClock
import com.calypsan.listenup.server.testing.seedTestBook
import com.calypsan.listenup.server.testing.seedTestLibraryAndFolder
import com.calypsan.listenup.server.testing.seedTestUser
import com.calypsan.listenup.server.testing.withSqlDatabase
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Access + control gating tests for [CampfireServiceImpl] over a real, migrated in-memory
 * database (via [withSqlDatabase]) and a real [CampfireRegistry] — the [PushServiceImplTest]
 * pattern. Anchor math and pure room-state behavior are [CampfireRegistry]'s own tests
 * ([com.calypsan.listenup.server.campfire.CampfireRegistryTest]); these tests exercise the
 * layer above it: book-access gating, host-only enforcement, the §7 spoiler check, and the
 * observeSession presence contract.
 */
class CampfireServiceImplTest :
    FunSpec({

        val t0 = Instant.fromEpochMilliseconds(1_730_000_000_000L)
        val everyoneSettings = CampfireSettings(controlMode = CampfireControlMode.EVERYONE, inviteOnly = false)
        val hostOnlySettings = CampfireSettings(controlMode = CampfireControlMode.HOST_ONLY, inviteOnly = false)

        fun principalFor(userId: String): PrincipalProvider =
            PrincipalProvider { UserPrincipal(UserId(userId), SessionId("session-$userId"), UserRole.MEMBER) }

        fun makeService(
            sql: ListenUpDatabase,
            driver: SqlDriver,
            registry: CampfireRegistry,
            principal: PrincipalProvider,
            clock: kotlin.time.Clock = FixedClock(t0),
        ): CampfireServiceImpl {
            val bus = ChangeBus()
            val syncRegistry = SyncRegistry()
            return CampfireServiceImpl(
                registry = registry,
                bookAccessPolicy = BookAccessPolicy(sql, driver),
                playbackPositions = PlaybackPositionRepository(db = sql, bus = bus, registry = syncRegistry),
                publicProfiles = PublicProfileRepository(db = sql, bus = bus, registry = syncRegistry),
                db = sql,
                clock = clock,
                principal = principal,
            )
        }

        fun <T> AppResult<T>.value(): T {
            this.shouldBeInstanceOf<AppResult.Success<T>>()
            return data
        }

        /** Makes [bookId] visible to [viewer] via the ALL_BOOKS pure-union path (see [SocialServiceTest]). */
        suspend fun makeBookAccessible(
            sql: ListenUpDatabase,
            driver: SqlDriver,
            bookId: String,
            viewer: String,
            allBooksId: String = "all-books",
        ) {
            val bus = ChangeBus()
            val syncRegistry = SyncRegistry()
            val collectionRepo = CollectionRepository(db = sql, bus = bus, registry = syncRegistry, driver = driver)
            val collectionBookRepo = CollectionBookRepository(db = sql, bus = bus, registry = syncRegistry, driver = driver)
            val grantRepo = CollectionGrantRepository(db = sql, bus = bus, registry = syncRegistry, driver = driver)
            collectionRepo.upsert(
                CollectionSyncPayload(
                    id = allBooksId,
                    libraryId = "test-library",
                    ownerId = "system",
                    name = "All Books",
                    isInbox = false,
                    revision = 0L,
                    updatedAt = 0L,
                ),
            )
            collectionBookRepo.upsert(
                CollectionBookSyncPayload(collectionId = allBooksId, bookId = bookId, createdAt = 0L, revision = 0L),
            )
            grantRepo.upsert(
                CollectionShareSyncPayload(
                    id = "grant-$viewer-$bookId",
                    collectionId = allBooksId,
                    sharedWithUserId = viewer,
                    sharedByUserId = "system",
                    permission = SharePermission.Read,
                    revision = 0L,
                    updatedAt = 0L,
                ),
            )
        }

        /** Directly inserts an unfinished playback position row for [userId]/[bookId]. */
        fun ListenUpDatabase.seedPosition(
            userId: String,
            bookId: String,
            positionMs: Long,
        ) {
            playbackPositionsQueries.insert(
                id = "$userId-$bookId",
                user_id = userId,
                book_id = bookId,
                position_ms = positionMs,
                last_played_at = 1L,
                finished = 0L,
                playback_speed = 1.0,
                current_chapter_id = null,
                revision = 0L,
                created_at = 1L,
                updated_at = 1L,
                deleted_at = null,
                client_op_id = null,
            )
        }

        /** Directly inserts a `book_chapters` row. */
        fun ListenUpDatabase.seedChapter(
            bookId: String,
            ordinal: Long,
            startTimeMs: Long,
        ) {
            bookChaptersQueries.insert(
                book_id = bookId,
                ordinal = ordinal,
                id = "$bookId-ch-$ordinal",
                title = "Chapter $ordinal",
                duration = 0L,
                start_time = startTimeMs,
            )
        }

        // ── Access gating ─────────────────────────────────────────────────────

        test("createSession fails BookAccessDenied when the caller lacks book access") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("member")
                sql.seedTestBook("book-a")
                runTest {
                    val service = makeService(sql, driver, CampfireRegistry(clock = FixedClock(t0)), principalFor("member"))

                    val result = service.createSession("book-a", everyoneSettings)

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
                    val registry = CampfireRegistry(clock = FixedClock(t0))
                    val created = makeService(sql, driver, registry, principalFor("host")).createSession("book-a", everyoneSettings).value()

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
                        makeService(sql, driver, CampfireRegistry(clock = FixedClock(t0)), principalFor("host"))
                            .createSession("book-a", everyoneSettings)
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
                    val registry = CampfireRegistry(clock = FixedClock(t0))
                    val hostService = makeService(sql, driver, registry, principalFor("host"))
                    val created = hostService.createSession("book-a", everyoneSettings).value()
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
                    val registry = CampfireRegistry(clock = FixedClock(t0))
                    val hostService = makeService(sql, driver, registry, principalFor("host"))
                    val created = hostService.createSession("book-a", everyoneSettings).value()
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
                    val registry = CampfireRegistry(clock = FixedClock(t0))
                    val hostService = makeService(sql, driver, registry, principalFor("host"))
                    val created = hostService.createSession("book-a", everyoneSettings).value()
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
                    val registry = CampfireRegistry(clock = FixedClock(t0))
                    val hostService = makeService(sql, driver, registry, principalFor("host"))
                    val created = hostService.createSession("book-a", everyoneSettings).value()
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
                    val registry = CampfireRegistry(clock = FixedClock(t0))
                    val hostService = makeService(sql, driver, registry, principalFor("host"))
                    val created = hostService.createSession("book-a", everyoneSettings).value()
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
                    val registry = CampfireRegistry(clock = FixedClock(t0))
                    val hostService = makeService(sql, driver, registry, principalFor("host"))
                    val created = hostService.createSession("book-a", everyoneSettings).value()
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
                    val registry = CampfireRegistry(clock = FixedClock(t0))
                    val hostService = makeService(sql, driver, registry, principalFor("host"))
                    val created = hostService.createSession("book-a", hostOnlySettings).value()
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
                    val registry = CampfireRegistry(clock = FixedClock(t0))
                    val created = makeService(sql, driver, registry, principalFor("host")).createSession("book-a", everyoneSettings).value()

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
                    val registry = CampfireRegistry(clock = FixedClock(t0))
                    val hostService = makeService(sql, driver, registry, principalFor("host"))
                    val created = hostService.createSession("book-a", everyoneSettings).value()
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

                    registry.snapshot(created.id)!!.members.first { it.userId == "member" }.isAway shouldBe true

                    registry.reapAwayMembers(now = t0 + 3.minutes)
                    advanceUntilIdle()

                    observedFrames.filterIsInstance<CampfireFrame.MemberLeft>().any { it.member.userId == "member" } shouldBe true
                    observerJob.cancel()
                }
            }
        }
    })
