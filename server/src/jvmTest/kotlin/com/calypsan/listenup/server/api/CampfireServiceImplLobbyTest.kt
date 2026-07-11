@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.campfire.CampfireFrame
import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.CampfirePhase
import com.calypsan.listenup.api.dto.campfire.PlaybackCommand
import com.calypsan.listenup.api.error.CampfireError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.push.PushPayload
import com.calypsan.listenup.api.result.AppResult
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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Lobby-phase tests for [CampfireServiceImpl] — the 2026-07-11 lobby amendment: host-gated
 * [CampfireServiceImpl.startSession] / [CampfireServiceImpl.updateSettings], the lobby-only
 * command rejection ([CampfireError.NotStarted]), invite-diff push notification, name boundary
 * validation, and [com.calypsan.listenup.api.dto.campfire.CampfireSnapshot.invitedPending]
 * enrichment. Same real-database + real-registry rig as [CampfireServiceImplAccessTest]; shared
 * fixtures live in `CampfireServiceImplTestFixtures.kt`.
 */
class CampfireServiceImplLobbyTest :
    FunSpec({

        // ── Lobby command rejection ────────────────────────────────────────────

        test("sendCommand while the room is in LOBBY fails NotStarted") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    val hostService =
                        makeService(sql, driver, CampfireRegistry(clock = FixedClock(campfireTestT0)), principalFor("host"))
                    val created = hostService.createSession("book-a", campfireEveryoneSettings).value()

                    val result = hostService.sendCommand(created.id, PlaybackCommand.Play(commandId = "c1"))

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<CampfireError.NotStarted>()
                }
            }
        }

        test("sendChat works while the room is in LOBBY") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    val hostService =
                        makeService(sql, driver, CampfireRegistry(clock = FixedClock(campfireTestT0)), principalFor("host"))
                    val created = hostService.createSession("book-a", campfireEveryoneSettings).value()

                    hostService.sendChat(created.id, "gathering up").value()
                }
            }
        }

        // ── startSession ───────────────────────────────────────────────────────

        test("startSession by the host emits CampfireStarted with a playing anchor and flips the phase to LIVE") {
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
                    created.phase shouldBe CampfirePhase.LOBBY
                    makeService(sql, driver, registry, principalFor("member")).joinSession(created.id).value()

                    val sub = async { registry.observe(created.id)!!.first { it is CampfireFrame.CampfireStarted } }
                    advanceUntilIdle()

                    hostService.startSession(created.id).value()

                    val frame = sub.await() as CampfireFrame.CampfireStarted
                    frame.anchor.isPlaying shouldBe true
                    frame.byUserId shouldBe "host"
                    registry.snapshot(created.id)!!.phase shouldBe CampfirePhase.LIVE
                }
            }
        }

        test("startSession by a non-host member fails NotController") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestUser("member")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    makeBookAccessible(sql, driver, "book-a", "member")
                    val registry = CampfireRegistry(clock = FixedClock(campfireTestT0))
                    val created =
                        makeService(sql, driver, registry, principalFor("host")).createSession("book-a", campfireEveryoneSettings).value()
                    val memberService = makeService(sql, driver, registry, principalFor("member"))
                    memberService.joinSession(created.id).value()

                    val result = memberService.startSession(created.id)

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<CampfireError.NotController>()
                }
            }
        }

        test("startSession on an unknown campfire fails CampfireNotFound") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                runTest {
                    val service =
                        makeService(sql, driver, CampfireRegistry(clock = FixedClock(campfireTestT0)), principalFor("host"))

                    val result = service.startSession(CampfireId("nope"))

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<CampfireError.CampfireNotFound>()
                }
            }
        }

        test("startSession broadcasts CampfiresChanged (phase changes discovery presentation)") {
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

                    val received = mutableListOf<SyncControl>()
                    val job = launch { bus.subscribeControl().collect { received += it.control } }
                    advanceUntilIdle()

                    hostService.startSession(created.id).value()
                    advanceUntilIdle()

                    received shouldBe listOf(SyncControl.CampfiresChanged)
                    job.cancel()
                }
            }
        }

        test("commands flow normally after startSession") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    val hostService =
                        makeService(sql, driver, CampfireRegistry(clock = FixedClock(campfireTestT0)), principalFor("host"))
                    val created = hostService.createSession("book-a", campfireEveryoneSettings).value()
                    hostService.startSession(created.id).value()

                    hostService.sendCommand(created.id, PlaybackCommand.Pause(commandId = "c1")).value()
                }
            }
        }

        // ── updateSettings ─────────────────────────────────────────────────────

        test("updateSettings by the host replaces settings and returns the refreshed snapshot") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    val hostService =
                        makeService(sql, driver, CampfireRegistry(clock = FixedClock(campfireTestT0)), principalFor("host"))
                    val created = hostService.createSession("book-a", campfireEveryoneSettings).value()
                    val renamed = campfireEveryoneSettings.copy(name = "Renamed Campfire")

                    val updated = hostService.updateSettings(created.id, renamed).value()

                    updated.settings.name shouldBe "Renamed Campfire"
                    updated.phase shouldBe CampfirePhase.LOBBY
                }
            }
        }

        test("updateSettings by a non-host member fails NotController") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestUser("member")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    makeBookAccessible(sql, driver, "book-a", "member")
                    val registry = CampfireRegistry(clock = FixedClock(campfireTestT0))
                    val created =
                        makeService(sql, driver, registry, principalFor("host")).createSession("book-a", campfireEveryoneSettings).value()
                    val memberService = makeService(sql, driver, registry, principalFor("member"))
                    memberService.joinSession(created.id).value()

                    val result = memberService.updateSettings(created.id, campfireEveryoneSettings.copy(name = "Hijacked"))

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<CampfireError.NotController>()
                }
            }
        }

        test("updateSettings after startSession fails (LOBBY-only)") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    val hostService =
                        makeService(sql, driver, CampfireRegistry(clock = FixedClock(campfireTestT0)), principalFor("host"))
                    val created = hostService.createSession("book-a", campfireEveryoneSettings).value()
                    hostService.startSession(created.id).value()

                    val result = hostService.updateSettings(created.id, campfireEveryoneSettings.copy(name = "Too Late"))

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ValidationError>()
                }
            }
        }

        test("updateSettings push-invites ONLY the newly-added accessible invitee") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestUser("early-invitee")
                sql.seedTestUser("late-invitee")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    makeBookAccessible(sql, driver, "book-a", "early-invitee")
                    makeBookAccessible(sql, driver, "book-a", "late-invitee")
                    val notifier = RecordingPushNotifier()
                    val hostService =
                        makeService(
                            sql,
                            driver,
                            CampfireRegistry(clock = FixedClock(campfireTestT0)),
                            principalFor("host"),
                            pushNotifier = notifier,
                        )
                    val created =
                        hostService
                            .createSession("book-a", campfireEveryoneSettings.copy(invitedUserIds = listOf("early-invitee")))
                            .value()
                    notifier.calls.map { it.first } shouldBe listOf("early-invitee")

                    hostService
                        .updateSettings(
                            created.id,
                            campfireEveryoneSettings.copy(invitedUserIds = listOf("early-invitee", "late-invitee")),
                        ).value()

                    // Only the addition is pushed — early-invitee is NOT re-notified.
                    notifier.calls.map { it.first } shouldBe listOf("early-invitee", "late-invitee")
                    notifier.calls
                        .last()
                        .second
                        .shouldBeInstanceOf<PushPayload.CampfireInvite>()
                        .campfireId shouldBe created.id.value
                }
            }
        }

        // ── invitedPending enrichment ──────────────────────────────────────────

        test("snapshots enrich invitedPending with display names for invited-but-not-joined users only") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestUser("joined-invitee")
                sql.seedTestUser("pending-invitee")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    makeBookAccessible(sql, driver, "book-a", "joined-invitee")
                    makeBookAccessible(sql, driver, "book-a", "pending-invitee")
                    val registry = CampfireRegistry(clock = FixedClock(campfireTestT0))
                    val hostService = makeService(sql, driver, registry, principalFor("host"))
                    val created =
                        hostService
                            .createSession(
                                "book-a",
                                campfireEveryoneSettings.copy(invitedUserIds = listOf("joined-invitee", "pending-invitee")),
                            ).value()
                    created.invitedPending.map { it.userId } shouldBe listOf("joined-invitee", "pending-invitee")

                    val joined =
                        makeService(sql, driver, registry, principalFor("joined-invitee")).joinSession(created.id).value()

                    // joined-invitee is now a member — only pending-invitee remains pending, with a display name.
                    joined.invitedPending.map { it.userId } shouldBe listOf("pending-invitee")
                    joined.invitedPending.single().displayName shouldBe
                        sql.usersQueries
                            .selectById("pending-invitee")
                            .executeAsOne()
                            .display_name
                }
            }
        }

        test("invitedPending is empty when every invitee has joined") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestUser("invitee")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    makeBookAccessible(sql, driver, "book-a", "invitee")
                    val registry = CampfireRegistry(clock = FixedClock(campfireTestT0))
                    val hostService = makeService(sql, driver, registry, principalFor("host"))
                    val created =
                        hostService
                            .createSession("book-a", campfireEveryoneSettings.copy(invitedUserIds = listOf("invitee")))
                            .value()

                    val joined = makeService(sql, driver, registry, principalFor("invitee")).joinSession(created.id).value()

                    joined.invitedPending.shouldBeEmpty()
                }
            }
        }

        // ── Name boundary validation ───────────────────────────────────────────

        test("createSession rejects a blank campfire name") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    val service =
                        makeService(sql, driver, CampfireRegistry(clock = FixedClock(campfireTestT0)), principalFor("host"))

                    val result = service.createSession("book-a", campfireEveryoneSettings.copy(name = "   "))

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ValidationError>()
                }
            }
        }

        test("createSession rejects a campfire name over 100 characters") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    val service =
                        makeService(sql, driver, CampfireRegistry(clock = FixedClock(campfireTestT0)), principalFor("host"))

                    val result = service.createSession("book-a", campfireEveryoneSettings.copy(name = "x".repeat(101)))

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ValidationError>()
                }
            }
        }

        test("updateSettings rejects a blank campfire name") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    val hostService =
                        makeService(sql, driver, CampfireRegistry(clock = FixedClock(campfireTestT0)), principalFor("host"))
                    val created = hostService.createSession("book-a", campfireEveryoneSettings).value()

                    val result = hostService.updateSettings(created.id, campfireEveryoneSettings.copy(name = ""))

                    result.shouldBeInstanceOf<AppResult.Failure>()
                    result.error.shouldBeInstanceOf<ValidationError>()
                }
            }
        }

        // ── Discovery carries phase + name ─────────────────────────────────────

        test("listOpenSessions carries the room's phase and name") {
            withSqlDatabase {
                sql.seedTestLibraryAndFolder()
                sql.seedTestUser("host")
                sql.seedTestBook("book-a")
                runTest {
                    makeBookAccessible(sql, driver, "book-a", "host")
                    val registry = CampfireRegistry(clock = FixedClock(campfireTestT0))
                    val hostService = makeService(sql, driver, registry, principalFor("host"))
                    val created =
                        hostService.createSession("book-a", campfireEveryoneSettings.copy(name = "Gathering Room")).value()

                    val lobbyListing = hostService.listOpenSessions().value().single()
                    lobbyListing.phase shouldBe CampfirePhase.LOBBY
                    lobbyListing.name shouldBe "Gathering Room"

                    hostService.startSession(created.id).value()

                    hostService
                        .listOpenSessions()
                        .value()
                        .single()
                        .phase shouldBe CampfirePhase.LIVE
                }
            }
        }
    })
