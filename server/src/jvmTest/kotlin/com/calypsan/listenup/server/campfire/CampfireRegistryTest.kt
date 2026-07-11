@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.campfire

import com.calypsan.listenup.api.dto.campfire.CampfireAnchor
import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireFrame
import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.api.dto.campfire.PlaybackCommand
import com.calypsan.listenup.server.testing.FixedClock
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class CampfireRegistryTest :
    FunSpec({

        val t0 = Instant.fromEpochMilliseconds(1_730_000_000_000L)
        val roomId = CampfireId("room-1")
        val bookId = "book-1"
        val settings = CampfireSettings(controlMode = CampfireControlMode.EVERYONE, inviteOnly = false)

        // ---- Anchor math (§3.2) ----

        test("posAt advances position by elapsed time while playing") {
            val anchor = CampfireAnchor(positionMs = 10_000, capturedAtEpochMs = t0.toEpochMilliseconds(), speed = 1f, isPlaying = true, stateVersion = 0)
            anchor.posAt(t0 + 5.seconds) shouldBe 15_000L
        }

        test("posAt scales elapsed time by speed while playing") {
            val anchor = CampfireAnchor(positionMs = 10_000, capturedAtEpochMs = t0.toEpochMilliseconds(), speed = 2f, isPlaying = true, stateVersion = 0)
            anchor.posAt(t0 + 5.seconds) shouldBe 20_000L
        }

        test("posAt is a fixed point while paused, regardless of elapsed time") {
            val anchor = CampfireAnchor(positionMs = 10_000, capturedAtEpochMs = t0.toEpochMilliseconds(), speed = 1f, isPlaying = false, stateVersion = 0)
            anchor.posAt(t0 + 1.hours) shouldBe 10_000L
        }

        // ---- Command application (§3.3, §9) ----

        test("Play resumes at the current computed position and bumps stateVersion") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings)

                val outcome = registry.applyCommand(roomId, "host", PlaybackCommand.Play(commandId = "c1"), now = t0 + 3.seconds)

                val applied = outcome as CommandOutcome.Applied
                applied.frame.anchor.isPlaying shouldBe true
                applied.frame.anchor.positionMs shouldBe 0L
                applied.frame.anchor.stateVersion shouldBe 1L
                applied.frame.byUserId shouldBe "host"
                applied.frame.commandId shouldBe "c1"
            }
        }

        test("Pause freezes at the computed elapsed position and bumps stateVersion") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings)
                registry.applyCommand(roomId, "host", PlaybackCommand.Play("c1"), now = t0)

                val outcome = registry.applyCommand(roomId, "host", PlaybackCommand.Pause("c2"), now = t0 + 4.seconds)

                val applied = outcome as CommandOutcome.Applied
                applied.frame.anchor.isPlaying shouldBe false
                applied.frame.anchor.positionMs shouldBe 4_000L
                applied.frame.anchor.stateVersion shouldBe 2L
            }
        }

        test("SeekTo sets the exact position and bumps stateVersion") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings)

                val outcome = registry.applyCommand(roomId, "host", PlaybackCommand.SeekTo(positionMs = 99_000L, commandId = "c1"), now = t0)

                val applied = outcome as CommandOutcome.Applied
                applied.frame.anchor.positionMs shouldBe 99_000L
                applied.frame.anchor.stateVersion shouldBe 1L
            }
        }

        test("SetSpeed re-anchors at the computed position and changes speed") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings)
                registry.applyCommand(roomId, "host", PlaybackCommand.Play("c1"), now = t0)

                val outcome = registry.applyCommand(roomId, "host", PlaybackCommand.SetSpeed(speed = 1.5f, commandId = "c2"), now = t0 + 2.seconds)

                val applied = outcome as CommandOutcome.Applied
                applied.frame.anchor.speed shouldBe 1.5f
                applied.frame.anchor.positionMs shouldBe 2_000L
                applied.frame.anchor.isPlaying shouldBe true
                applied.frame.anchor.stateVersion shouldBe 2L
            }
        }

        test("Pause on an already-paused room no-ops: no version bump, no frame") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings)

                registry.applyCommand(roomId, "host", PlaybackCommand.Pause("c1"), now = t0) shouldBe CommandOutcome.NoOp
                registry.snapshot(roomId)!!.anchor.stateVersion shouldBe 0L
            }
        }

        test("Play on an already-playing room no-ops: no version bump, no frame") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings)
                registry.applyCommand(roomId, "host", PlaybackCommand.Play("c1"), now = t0)

                registry.applyCommand(roomId, "host", PlaybackCommand.Play("c2"), now = t0 + 1.seconds) shouldBe CommandOutcome.NoOp
                registry.snapshot(roomId)!!.anchor.stateVersion shouldBe 1L
            }
        }

        test("a stale expectedStateVersion is rejected outright") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings)
                registry.applyCommand(roomId, "host", PlaybackCommand.Play("c1"), now = t0) // bumps to version 1

                val outcome =
                    registry.applyCommand(
                        roomId,
                        "host",
                        PlaybackCommand.Pause(commandId = "c2", expectedStateVersion = 0L),
                        now = t0 + 1.seconds,
                    )

                outcome shouldBe CommandOutcome.Rejected
                registry.snapshot(roomId)!!.anchor.stateVersion shouldBe 1L
                registry.snapshot(roomId)!!.anchor.isPlaying shouldBe true
            }
        }

        test("a matching expectedStateVersion applies normally") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings)
                registry.applyCommand(roomId, "host", PlaybackCommand.Play("c1"), now = t0) // bumps to version 1

                val outcome =
                    registry.applyCommand(
                        roomId,
                        "host",
                        PlaybackCommand.Pause(commandId = "c2", expectedStateVersion = 1L),
                        now = t0 + 1.seconds,
                    )

                val applied = outcome as CommandOutcome.Applied
                applied.frame.anchor.isPlaying shouldBe false
                applied.frame.anchor.stateVersion shouldBe 2L
            }
        }

        test("a command from a non-member returns NotAMember") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings)

                registry.applyCommand(roomId, "ghost", PlaybackCommand.Play("c1")) shouldBe CommandOutcome.NotAMember
            }
        }

        test("a command against an unknown room returns RoomNotFound") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))

                registry.applyCommand(CampfireId("does-not-exist"), "host", PlaybackCommand.Play("c1")) shouldBe CommandOutcome.RoomNotFound
            }
        }

        // ---- Membership (§4) ----

        test("join adds members and emits MemberJoined") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings)

                val sub = async { registry.observe(roomId)!!.first() }
                advanceUntilIdle()

                val outcome = registry.join(roomId, "u2", "Two")

                val joined = outcome as JoinOutcome.Joined
                joined.snapshot.members.map { it.userId } shouldContainExactly listOf("host", "u2")
                (sub.await() as CampfireFrame.MemberJoined).member.userId shouldBe "u2"
            }
        }

        test("the 9th join to an 8-member room is rejected with RoomFull") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings) // 1 member (host)
                (2..8).forEach { i -> registry.join(roomId, "u$i", "User $i") } // 7 more members = 8 total

                registry.join(roomId, "u9", "User Nine") shouldBe JoinOutcome.RoomFull
            }
        }

        test("rejoin of an existing member succeeds without duplicating membership") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings)
                registry.join(roomId, "u2", "Two")

                val outcome = registry.join(roomId, "u2", "Two")

                val joined = outcome as JoinOutcome.Joined
                joined.snapshot.members shouldHaveSize 2
            }
        }

        // ---- Away / grace eviction (§4) ----

        test("markAway emits MemberAway") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings)
                registry.join(roomId, "u2", "Two")

                val sub = async { registry.observe(roomId)!!.first { it is CampfireFrame.MemberAway } }
                advanceUntilIdle()

                registry.markAway(roomId, "u2")

                (sub.await() as CampfireFrame.MemberAway).member.userId shouldBe "u2"
            }
        }

        test("reapAwayMembers evicts a member only once the grace window has elapsed") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings)
                registry.join(roomId, "u2", "Two")
                registry.markAway(roomId, "u2", now = t0)

                registry.reapAwayMembers(now = t0 + 1.minutes) // before the 2-minute grace
                registry.snapshot(roomId)!!.members.map { it.userId } shouldContain "u2"

                registry.reapAwayMembers(now = t0 + 2.minutes + 1.seconds) // past grace
                registry.snapshot(roomId)!!.members.map { it.userId } shouldNotContain "u2"
            }
        }

        test("rejoining within the grace window clears away and survives the reap") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings)
                registry.join(roomId, "u2", "Two")
                registry.markAway(roomId, "u2", now = t0)

                registry.clearAway(roomId, "u2", now = t0 + 30.seconds)
                registry.reapAwayMembers(now = t0 + 3.minutes)

                registry.snapshot(roomId)!!.members.map { it.userId } shouldContain "u2"
            }
        }

        // ---- Host handoff (§4) ----

        test("explicit host leave transfers host to the longest-present remaining member") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings, now = t0)
                registry.join(roomId, "u2", "Two", now = t0 + 1.seconds)
                registry.join(roomId, "u3", "Three", now = t0 + 2.seconds)

                registry.leave(roomId, "host", now = t0 + 5.seconds)

                registry.snapshot(roomId)!!.hostUserId shouldBe "u2"
            }
        }

        test("host away-eviction transfers host to the longest-present remaining member") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings, now = t0)
                registry.join(roomId, "u2", "Two", now = t0 + 1.seconds)
                registry.join(roomId, "u3", "Three", now = t0 + 2.seconds)
                registry.markAway(roomId, "host", now = t0 + 3.seconds)

                registry.reapAwayMembers(now = t0 + 3.minutes)

                registry.snapshot(roomId)!!.hostUserId shouldBe "u2"
            }
        }

        test("the last member leaving ends the room") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings)

                registry.leave(roomId, "host") shouldBe LeaveOutcome.RoomEnded

                registry.snapshot(roomId) shouldBe null
            }
        }

        // ---- Explicit host transfer + control mode (Task 3 wiring) ----

        test("transferHost moves the host role and broadcasts HostChanged") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings)
                registry.join(roomId, "u2", "Two")

                val outcome = registry.transferHost(roomId, "u2")

                val transferred = outcome as TransferHostOutcome.Transferred
                transferred.frame.userId shouldBe "u2"
                registry.snapshot(roomId)!!.hostUserId shouldBe "u2"
            }
        }

        test("transferHost to a non-member returns TargetNotAMember") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings)

                registry.transferHost(roomId, "ghost") shouldBe TransferHostOutcome.TargetNotAMember
                registry.snapshot(roomId)!!.hostUserId shouldBe "host"
            }
        }

        test("transferHost against an unknown room returns RoomNotFound") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))

                registry.transferHost(CampfireId("does-not-exist"), "u2") shouldBe TransferHostOutcome.RoomNotFound
            }
        }

        test("setControlMode changes the room's control mode and broadcasts ControlModeChanged") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings) // starts EVERYONE

                val outcome = registry.setControlMode(roomId, CampfireControlMode.HOST_ONLY)

                val applied = outcome as SetControlModeOutcome.Applied
                applied.frame.mode shouldBe CampfireControlMode.HOST_ONLY
                registry.snapshot(roomId)!!.settings.controlMode shouldBe CampfireControlMode.HOST_ONLY
            }
        }

        test("setControlMode against an unknown room returns RoomNotFound") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))

                registry.setControlMode(
                    CampfireId("does-not-exist"),
                    CampfireControlMode.HOST_ONLY,
                ) shouldBe SetControlModeOutcome.RoomNotFound
            }
        }

        // ---- Ending (§4) ----

        test("endSession broadcasts CampfireEnded to every subscriber and removes the room") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings)

                val sub1 = async { registry.observe(roomId)!!.first() }
                val sub2 = async { registry.observe(roomId)!!.first() }
                advanceUntilIdle()

                registry.endSession(roomId, reason = CAMPFIRE_END_REASON_HOST_ENDED)

                (sub1.await() as CampfireFrame.CampfireEnded).reason shouldBe CAMPFIRE_END_REASON_HOST_ENDED
                (sub2.await() as CampfireFrame.CampfireEnded).reason shouldBe CAMPFIRE_END_REASON_HOST_ENDED
                registry.snapshot(roomId) shouldBe null
            }
        }

        // ---- Shared flow fan-out ----

        test("two subscribers both receive the same frame") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings)

                val sub1 = async { registry.observe(roomId)!!.first() }
                val sub2 = async { registry.observe(roomId)!!.first() }
                advanceUntilIdle()

                registry.join(roomId, "u2", "Two")

                (sub1.await() as CampfireFrame.MemberJoined).member.userId shouldBe "u2"
                (sub2.await() as CampfireFrame.MemberJoined).member.userId shouldBe "u2"
            }
        }

        // ---- Chat (§5) ----

        test("the chat ring buffer caps at 50 messages, dropping the oldest") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings)

                repeat(51) { i -> registry.sendChat(roomId, "host", "msg$i", now = t0 + i.seconds) }

                val recent = registry.snapshot(roomId)!!.recentChat
                recent shouldHaveSize 50
                recent.first().text shouldBe "msg1" // msg0 was dropped
                recent.last().text shouldBe "msg50"
            }
        }

        test("sendChat emits a Chat frame stamped with the room's computed position") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings)
                registry.applyCommand(roomId, "host", PlaybackCommand.Play("c1"), now = t0)

                val outcome = registry.sendChat(roomId, "host", "hello", now = t0 + 10.seconds)

                val sent = outcome as ChatOutcome.Sent
                sent.message.positionMs shouldBe 10_000L
                sent.message.text shouldBe "hello"
            }
        }

        // ---- Reactions (§5) ----

        test("the reaction burst limit drops excess reactions silently") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings)

                repeat(5) { registry.sendReaction(roomId, "host", "🔥", now = t0) shouldBe ReactionOutcome.Sent }

                registry.sendReaction(roomId, "host", "🔥", now = t0) shouldBe ReactionOutcome.Dropped
            }
        }

        test("the reaction burst limit resets once the window elapses") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings)
                repeat(5) { registry.sendReaction(roomId, "host", "🔥", now = t0) }

                registry.sendReaction(roomId, "host", "🔥", now = t0 + 11.seconds) shouldBe ReactionOutcome.Sent
            }
        }

        // ---- Idle sweeper (§4) ----

        test("reapIdle ends a room with no activity past the idle timeout") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings, now = t0)

                val ended = registry.reapIdle(now = t0 + 61.minutes)

                ended shouldContainExactly listOf(roomId)
                registry.snapshot(roomId) shouldBe null
            }
        }

        test("reapIdle leaves a room with recent activity untouched") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))
                registry.createRoom(roomId, bookId, "host", "Host", settings, now = t0)
                registry.sendChat(roomId, "host", "still here", now = t0 + 59.minutes)

                val ended = registry.reapIdle(now = t0 + 61.minutes)

                ended.shouldBeEmpty()
                registry.snapshot(roomId) shouldNotBe null
            }
        }

        // ---- Snapshot assembly ----

        test("snapshot exposes anchor, members, host, and recent chat; per-caller fields are left null/false") {
            runTest {
                val registry = CampfireRegistry(clock = FixedClock(t0))

                val created = registry.createRoom(roomId, bookId, "host", "Host", settings, startingPositionMs = 500L, now = t0)

                created.anchor.positionMs shouldBe 500L
                created.hostUserId shouldBe "host"
                created.members.map { it.userId } shouldContainExactly listOf("host")
                created.recentChat.shouldBeEmpty()
                created.yourPositionMs shouldBe null
                created.spoilerAhead shouldBe false
            }
        }
    })
