@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.campfire

import com.calypsan.listenup.api.dto.campfire.CampfireAnchor
import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireFrame
import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.CampfireMember
import com.calypsan.listenup.api.dto.campfire.CampfirePhase
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.api.dto.campfire.CampfireSnapshot
import com.calypsan.listenup.api.dto.campfire.ChatMessage
import com.calypsan.listenup.api.dto.campfire.PlaybackCommand
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Computes where this anchor's playback has reached at [now] — the co-listening design spec
 * §3.2 formula, and the reason the server never streams a position tick. A playing anchor
 * advances at [CampfireAnchor.speed] from [CampfireAnchor.capturedAtEpochMs]; a paused anchor
 * is a fixed point at [CampfireAnchor.positionMs] regardless of how much time has passed.
 */
fun CampfireAnchor.posAt(now: Instant): Long {
    if (!isPlaying) return positionMs
    val elapsedMs = now.toEpochMilliseconds() - capturedAtEpochMs
    return positionMs + (elapsedMs * speed).toLong()
}

/** [CampfireFrame.CampfireEnded] reason when the host explicitly ends the room. */
const val CAMPFIRE_END_REASON_HOST_ENDED = "host_ended"

/** [CampfireFrame.CampfireEnded] reason when the last member leaves and the room closes itself. */
const val CAMPFIRE_END_REASON_EMPTY = "empty"

/** [CampfireFrame.CampfireEnded] reason when the idle sweeper reaps a room past its activity timeout. */
const val CAMPFIRE_END_REASON_IDLE = "idle"

/**
 * One live, in-memory campfire room: its anchor timeline, membership, chat ring, and reaction
 * rate limiter. Owned exclusively by [CampfireRegistry] — every mutation here is serialized by
 * this room's own [Mutex], independent of every other room's.
 *
 * Ephemeral by design (co-listening design spec §3.1/§3.5): no persistence, gone when the room
 * ends or the process restarts.
 */
internal class CampfireRoom(
    val id: CampfireId,
    val bookId: String,
    settings: CampfireSettings,
    hostUserId: String,
    hostDisplayName: String?,
    startingPositionMs: Long,
    now: Instant,
    private val maxMembers: Int,
    private val chatRingSize: Int,
    private val reactionBurstLimit: Int,
    private val reactionBurstWindow: Duration,
) {
    private val mutex = Mutex()
    private val mutableFrames = MutableSharedFlow<CampfireFrame>(replay = 0, extraBufferCapacity = 64)

    /** The room's broadcast frame flow — every subscriber sees every frame from the moment it subscribes. */
    val frames: SharedFlow<CampfireFrame> = mutableFrames.asSharedFlow()

    private var settings = settings
    private var anchor =
        CampfireAnchor(
            positionMs = startingPositionMs,
            capturedAtEpochMs = now.toEpochMilliseconds(),
            speed = 1f,
            isPlaying = false,
            stateVersion = 0,
        )
    private var hostUserId = hostUserId
    private var ended = false

    /** The room's lifecycle phase — the co-listening lobby amendment (2026-07-11). Every room is born in [CampfirePhase.LOBBY]. */
    private var phase = CampfirePhase.LOBBY

    /** Server epoch-ms of [start], or `null` while still in [CampfirePhase.LOBBY]. */
    private var startedAtEpochMs: Long? = null
    private val members = LinkedHashMap<String, MemberState>()
    private val chatRing = ArrayDeque<ChatMessage>()
    private val reactionTimestamps = mutableMapOf<String, ArrayDeque<Instant>>()

    /**
     * Every user id that has ever actually joined this room — the host counts from creation.
     * Grows monotonically; a leave/away-eviction never removes an id, since the "listened
     * together" activity (see [CampfireEndInfo]) credits everyone who was ever present, not just
     * who was still there at the end.
     */
    private val everJoinedUserIds = linkedSetOf(hostUserId)

    /** Instant of the room's most recent join/leave/command/chat/reaction — the idle-reaper's clock. */
    var lastActivityAt: Instant = now
        private set

    init {
        members[hostUserId] = MemberState(hostUserId, hostDisplayName, now.toEpochMilliseconds())
    }

    private class MemberState(
        val userId: String,
        val displayName: String?,
        val joinedAtEpochMs: Long,
    ) {
        var awaySinceEpochMs: Long? = null
    }

    private fun MemberState.toDto(invited: Boolean = false) =
        CampfireMember(
            userId = userId,
            displayName = displayName,
            joinedAtEpochMs = joinedAtEpochMs,
            isAway = awaySinceEpochMs != null,
            invited = invited,
        )

    private fun snapshotLocked(): CampfireSnapshot =
        CampfireSnapshot(
            id = id,
            bookId = bookId,
            settings = settings,
            phase = phase,
            anchor = anchor,
            members = members.values.map { it.toDto() },
            hostUserId = hostUserId,
            recentChat = chatRing.toList(),
            // yourPositionMs / spoilerAhead are per-caller concerns computed by the service layer (Task 3).
            yourPositionMs = null,
            spoilerAhead = false,
            startedAtEpochMs = startedAtEpochMs,
            // invitedPending needs display-name enrichment, a service-layer (DB) concern — see
            // CampfireServiceImpl.withInvitedPending.
            invitedPending = emptyList(),
        )

    /** Current state, unscoped to a caller — [CampfireSnapshot.yourPositionMs]/[CampfireSnapshot.spoilerAhead] are always null/false here. */
    suspend fun snapshot(): CampfireSnapshot = mutex.withLock { snapshotLocked() }

    /** The room's current host id — read by [CampfireRegistry] to build a [CampfireEndInfo] after [end]. */
    suspend fun currentHostUserId(): String = mutex.withLock { hostUserId }

    /** See [everJoinedUserIds] — read by [CampfireRegistry] to build a [CampfireEndInfo]. */
    suspend fun participantIds(): Set<String> = mutex.withLock { everJoinedUserIds.toSet() }

    /**
     * Adds [userId] as a member, or — if already a member — clears any away flag (a reconnect).
     * Returns [JoinOutcome.RoomFull] at the [maxMembers] cap for a genuinely new member.
     */
    suspend fun join(
        userId: String,
        displayName: String?,
        now: Instant,
    ): JoinOutcome =
        mutex.withLock {
            if (ended) return@withLock JoinOutcome.RoomNotFound
            lastActivityAt = now
            val existing = members[userId]
            if (existing != null) {
                existing.awaySinceEpochMs = null
                return@withLock JoinOutcome.Joined(snapshotLocked())
            }
            if (members.size >= maxMembers) return@withLock JoinOutcome.RoomFull
            val member = MemberState(userId, displayName, now.toEpochMilliseconds())
            members[userId] = member
            everJoinedUserIds += userId
            mutableFrames.emit(CampfireFrame.MemberJoined(member.toDto()))
            JoinOutcome.Joined(snapshotLocked())
        }

    /**
     * Removes [userId] outright (explicit leave). If the departing member was the sole member,
     * the room ends itself ([CAMPFIRE_END_REASON_EMPTY]); if they were host, the role transfers
     * to the longest-present remaining member.
     */
    suspend fun leave(
        userId: String,
        now: Instant,
    ): LeaveOutcome =
        mutex.withLock {
            if (ended) return@withLock LeaveOutcome.RoomNotFound
            lastActivityAt = now
            val removed = removeMemberLocked(userId, now) ?: return@withLock LeaveOutcome.NotAMember
            if (ended) {
                LeaveOutcome.RoomEnded(CampfireEndInfo(id, bookId, hostUserId, everJoinedUserIds.toSet()))
            } else {
                LeaveOutcome.Left(removed.toDto())
            }
        }

    /** Marks [userId] away (flow disconnect). No-op if already away or not a member. */
    suspend fun markAway(
        userId: String,
        now: Instant,
    ) {
        mutex.withLock {
            if (ended) return@withLock
            val member = members[userId] ?: return@withLock
            if (member.awaySinceEpochMs != null) return@withLock
            lastActivityAt = now
            member.awaySinceEpochMs = now.toEpochMilliseconds()
            mutableFrames.emit(CampfireFrame.MemberAway(member.toDto()))
        }
    }

    /** Clears [userId]'s away flag (a reconnect within the grace window). No-op if not a member. */
    suspend fun clearAway(
        userId: String,
        now: Instant,
    ) {
        mutex.withLock {
            if (ended) return@withLock
            val member = members[userId] ?: return@withLock
            lastActivityAt = now
            member.awaySinceEpochMs = null
        }
    }

    /**
     * Evicts every member who has been away longer than [grace], each eviction going through the
     * same [removeMemberLocked] path as an explicit [leave] (host handoff / room end included).
     * Returns `true` if the room has ended (by this call, or already).
     */
    suspend fun reapAwayMembers(
        now: Instant,
        grace: Duration,
    ): Boolean =
        mutex.withLock {
            if (ended) return@withLock true
            val staleUserIds =
                members.values
                    .filter { member ->
                        val awaySince = member.awaySinceEpochMs
                        awaySince != null && now.toEpochMilliseconds() - awaySince >= grace.inWholeMilliseconds
                    }.map { it.userId }
            for (userId in staleUserIds) {
                if (ended) break
                removeMemberLocked(userId, now)
            }
            ended
        }

    /** Removes [userId], handling room-end and host-handoff consequences. Caller must hold [mutex]. */
    private suspend fun removeMemberLocked(
        userId: String,
        now: Instant,
    ): MemberState? {
        val member = members.remove(userId) ?: return null
        mutableFrames.emit(CampfireFrame.MemberLeft(member.toDto()))
        if (members.isEmpty()) {
            endLocked(CAMPFIRE_END_REASON_EMPTY, now)
        } else if (hostUserId == userId) {
            val newHost = members.values.minBy { it.joinedAtEpochMs }
            hostUserId = newHost.userId
            mutableFrames.emit(CampfireFrame.HostChanged(hostUserId))
        }
        return member
    }

    /** Ends the room for everyone with [reason], unless it has already ended. */
    suspend fun end(
        now: Instant,
        reason: String,
    ) {
        mutex.withLock {
            if (!ended) endLocked(reason, now)
        }
    }

    private suspend fun endLocked(
        reason: String,
        now: Instant,
    ) {
        ended = true
        lastActivityAt = now
        mutableFrames.emit(CampfireFrame.CampfireEnded(reason))
    }

    /**
     * Transfers the host role to [toUserId], unconditionally — the service layer has already
     * confirmed the caller is the current host before calling this (see [TransferHostOutcome]).
     * Rejects only when [toUserId] isn't a member.
     */
    suspend fun transferHost(
        toUserId: String,
        now: Instant,
    ): TransferHostOutcome =
        mutex.withLock {
            if (ended) return@withLock TransferHostOutcome.RoomNotFound
            if (toUserId !in members) return@withLock TransferHostOutcome.TargetNotAMember
            lastActivityAt = now
            hostUserId = toUserId
            val frame = CampfireFrame.HostChanged(toUserId)
            mutableFrames.emit(frame)
            TransferHostOutcome.Transferred(frame)
        }

    /**
     * Changes the room's control mode, unconditionally — the service layer has already confirmed
     * the caller is the current host before calling this (see [SetControlModeOutcome]).
     */
    suspend fun setControlMode(
        mode: CampfireControlMode,
        now: Instant,
    ): SetControlModeOutcome =
        mutex.withLock {
            if (ended) return@withLock SetControlModeOutcome.RoomNotFound
            lastActivityAt = now
            settings = settings.copy(controlMode = mode)
            val frame = CampfireFrame.ControlModeChanged(mode)
            mutableFrames.emit(frame)
            SetControlModeOutcome.Applied(frame)
        }

    /**
     * Starts the room: [CampfirePhase.LOBBY] -> [CampfirePhase.LIVE]. Re-anchors at [now] —
     * [CampfireAnchor.positionMs] unchanged (the anchor is a fixed point while paused, so there's
     * nothing to compute), [CampfireAnchor.capturedAtEpochMs] set to [now],
     * [CampfireAnchor.isPlaying] set `true`, [CampfireAnchor.stateVersion] bumped — and broadcasts
     * a single [CampfireFrame.CampfireStarted] (not [CampfireFrame.AnchorChanged]: this is the
     * shared start moment, not an ordinary command result). Idempotent: starting an
     * already-[CampfirePhase.LIVE] room is [StartOutcome.AlreadyLive], not an error.
     */
    suspend fun start(
        byUserId: String,
        now: Instant,
    ): StartOutcome =
        mutex.withLock {
            if (ended) return@withLock StartOutcome.RoomNotFound
            if (phase == CampfirePhase.LIVE) return@withLock StartOutcome.AlreadyLive
            lastActivityAt = now
            phase = CampfirePhase.LIVE
            startedAtEpochMs = now.toEpochMilliseconds()
            anchor =
                anchor.copy(
                    capturedAtEpochMs = now.toEpochMilliseconds(),
                    isPlaying = true,
                    stateVersion = anchor.stateVersion + 1,
                )
            val frame = CampfireFrame.CampfireStarted(anchor = anchor, byUserId = byUserId)
            mutableFrames.emit(frame)
            StartOutcome.Started(frame)
        }

    /**
     * Replaces the room's [CampfireSettings] while still in [CampfirePhase.LOBBY]
     * ([UpdateSettingsOutcome.RejectedLive] once [CampfirePhase.LIVE]). The service layer diffs
     * [newSettings] against the previous settings (read via [snapshot] before calling this) to
     * push-invite only newly-added users, and enriches the returned snapshot's
     * [CampfireSnapshot.invitedPending] — this method only replaces the settings and returns the
     * room's fresh unscoped snapshot.
     */
    suspend fun updateSettings(
        newSettings: CampfireSettings,
        now: Instant,
    ): UpdateSettingsOutcome =
        mutex.withLock {
            if (ended) return@withLock UpdateSettingsOutcome.RoomNotFound
            if (phase != CampfirePhase.LOBBY) return@withLock UpdateSettingsOutcome.RejectedLive
            lastActivityAt = now
            settings = newSettings
            UpdateSettingsOutcome.Applied(snapshotLocked())
        }

    /**
     * Applies [command] against the current anchor. Rejects outright while the room is still in
     * [CampfirePhase.LOBBY] ([CommandOutcome.NotStarted] — no bump, no frame; see [start]). Rejects
     * a stale [PlaybackCommand.expectedStateVersion] outright ([CommandOutcome.Rejected] — no
     * bump, no frame — the §9 simultaneous-commands rule); no-ops a play-on-playing or
     * pause-on-paused command ([CommandOutcome.NoOp]); otherwise re-anchors at [now] and bumps
     * [CampfireAnchor.stateVersion].
     */
    suspend fun applyCommand(
        userId: String,
        command: PlaybackCommand,
        now: Instant,
    ): CommandOutcome =
        mutex.withLock {
            if (ended) return@withLock CommandOutcome.RoomNotFound
            if (userId !in members) return@withLock CommandOutcome.NotAMember
            if (phase == CampfirePhase.LOBBY) return@withLock CommandOutcome.NotStarted
            val expected = command.expectedStateVersion
            if (expected != null && expected != anchor.stateVersion) return@withLock CommandOutcome.Rejected
            lastActivityAt = now
            val computedPositionMs = anchor.posAt(now)
            val nextAnchor =
                when (command) {
                    is PlaybackCommand.Play -> {
                        if (anchor.isPlaying) {
                            return@withLock CommandOutcome.NoOp
                        } else {
                            anchor.copy(
                                positionMs = computedPositionMs,
                                capturedAtEpochMs = now.toEpochMilliseconds(),
                                isPlaying = true,
                                stateVersion = anchor.stateVersion + 1,
                            )
                        }
                    }

                    is PlaybackCommand.Pause -> {
                        if (!anchor.isPlaying) {
                            return@withLock CommandOutcome.NoOp
                        } else {
                            anchor.copy(
                                positionMs = computedPositionMs,
                                capturedAtEpochMs = now.toEpochMilliseconds(),
                                isPlaying = false,
                                stateVersion = anchor.stateVersion + 1,
                            )
                        }
                    }

                    is PlaybackCommand.SeekTo -> {
                        anchor.copy(
                            positionMs = command.positionMs,
                            capturedAtEpochMs = now.toEpochMilliseconds(),
                            stateVersion = anchor.stateVersion + 1,
                        )
                    }

                    is PlaybackCommand.SetSpeed -> {
                        anchor.copy(
                            positionMs = computedPositionMs,
                            capturedAtEpochMs = now.toEpochMilliseconds(),
                            speed = command.speed,
                            stateVersion = anchor.stateVersion + 1,
                        )
                    }
                }
            anchor = nextAnchor
            val frame =
                CampfireFrame.AnchorChanged(
                    anchor = nextAnchor,
                    byUserId = userId,
                    commandId = command.commandId,
                )
            mutableFrames.emit(frame)
            CommandOutcome.Applied(frame)
        }

    /** Appends a chat message, trims the ring buffer to [chatRingSize], and broadcasts a [CampfireFrame.Chat]. */
    suspend fun sendChat(
        userId: String,
        text: String,
        now: Instant,
    ): ChatOutcome =
        mutex.withLock {
            if (ended) return@withLock ChatOutcome.RoomNotFound
            if (userId !in members) return@withLock ChatOutcome.NotAMember
            lastActivityAt = now
            val message =
                ChatMessage(
                    senderId = userId,
                    sentAtEpochMs = now.toEpochMilliseconds(),
                    positionMs = anchor.posAt(now),
                    text = text,
                )
            chatRing.addLast(message)
            if (chatRing.size > chatRingSize) chatRing.removeFirst()
            mutableFrames.emit(CampfireFrame.Chat(message))
            ChatOutcome.Sent(message)
        }

    /**
     * Broadcasts a fire-and-forget reaction, subject to a per-member sliding-window burst limit
     * ([reactionBurstLimit] per [reactionBurstWindow]). Excess reactions are dropped silently —
     * see [ReactionOutcome.Dropped].
     */
    suspend fun sendReaction(
        userId: String,
        emoji: String,
        now: Instant,
    ): ReactionOutcome =
        mutex.withLock {
            if (ended) return@withLock ReactionOutcome.RoomNotFound
            if (userId !in members) return@withLock ReactionOutcome.NotAMember
            lastActivityAt = now
            val recent = reactionTimestamps.getOrPut(userId) { ArrayDeque() }
            while (recent.isNotEmpty() && now - recent.first() > reactionBurstWindow) recent.removeFirst()
            if (recent.size >= reactionBurstLimit) return@withLock ReactionOutcome.Dropped
            recent.addLast(now)
            mutableFrames.emit(CampfireFrame.Reaction(userId, emoji))
            ReactionOutcome.Sent
        }
}
