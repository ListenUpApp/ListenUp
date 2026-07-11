@file:OptIn(ExperimentalTime::class)

package com.calypsan.listenup.server.campfire

import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireFrame
import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.CampfireMember
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.api.dto.campfire.CampfireSnapshot
import com.calypsan.listenup.api.dto.campfire.ChatMessage
import com.calypsan.listenup.api.dto.campfire.PlaybackCommand
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private val log = loggerFor<CampfireRegistry>()

private const val DEFAULT_MAX_MEMBERS = 8
private val DEFAULT_AWAY_GRACE = 2.minutes
private val DEFAULT_IDLE_TIMEOUT = 60.minutes
private const val DEFAULT_CHAT_RING_SIZE = 50
private const val DEFAULT_REACTION_BURST_LIMIT = 5
private val DEFAULT_REACTION_BURST_WINDOW = 10.seconds

/** Result of [CampfireRegistry.join]. */
sealed interface JoinOutcome {
    /** The caller is now (or already was) a member; [snapshot] is their post-join view of the room. */
    data class Joined(
        val snapshot: CampfireSnapshot,
    ) : JoinOutcome

    /** The room already has its full complement of members — the service layer maps this to `CampfireError.CampfireFull`. */
    data object RoomFull : JoinOutcome

    /** No such room (never existed, already ended, or reaped). */
    data object RoomNotFound : JoinOutcome
}

/** Result of [CampfireRegistry.leave]. */
sealed interface LeaveOutcome {
    /** The caller left; the room continues with at least one other member. */
    data class Left(
        val member: CampfireMember,
    ) : LeaveOutcome

    /** The caller was the last member; the room ended itself as a consequence. */
    data object RoomEnded : LeaveOutcome

    /** The caller was never a member of this room. */
    data object NotAMember : LeaveOutcome

    /** No such room. */
    data object RoomNotFound : LeaveOutcome
}

/**
 * Result of [CampfireRegistry.applyCommand] — the rejection surface for the co-listening design
 * spec §9 simultaneous-commands rule. The service layer (Task 3) maps [Rejected], [NotAMember],
 * and [RoomNotFound] to typed `CampfireError`s; [NoOp] and [Applied] both mean "the command was
 * accepted," just with or without a resulting frame.
 */
sealed interface CommandOutcome {
    /** The command applied; [frame] is what was broadcast (and is also the sender's echo-match). */
    data class Applied(
        val frame: CampfireFrame.AnchorChanged,
    ) : CommandOutcome

    /** Play-on-playing or pause-on-paused: accepted, but nothing changed, so no frame was broadcast. */
    data object NoOp : CommandOutcome

    /**
     * The command's `expectedStateVersion` no longer matches the room's current `stateVersion` —
     * a losing entry in the §9 simultaneous-commands race. Rejected outright: no version bump,
     * no frame. The caller is expected to re-fetch state and retry if still relevant.
     */
    data object Rejected : CommandOutcome

    /** The caller is not a member of this room. */
    data object NotAMember : CommandOutcome

    /** No such room. */
    data object RoomNotFound : CommandOutcome
}

/** Result of [CampfireRegistry.sendChat]. */
sealed interface ChatOutcome {
    /** The message was appended to the ring buffer and broadcast. */
    data class Sent(
        val message: ChatMessage,
    ) : ChatOutcome

    /** The caller is not a member of this room. */
    data object NotAMember : ChatOutcome

    /** No such room. */
    data object RoomNotFound : ChatOutcome
}

/**
 * Result of [CampfireRegistry.transferHost]. Host-only enforcement is the service layer's job
 * (Task 3 reads [CampfireSnapshot.hostUserId] and maps a non-host caller to `CampfireError.NotController`
 * before ever calling this) — the registry only validates that [toUserId] is a real member.
 */
sealed interface TransferHostOutcome {
    /** The host role transferred; [frame] is what was broadcast. */
    data class Transferred(
        val frame: CampfireFrame.HostChanged,
    ) : TransferHostOutcome

    /** [toUserId] passed to [CampfireRegistry.transferHost] is not a member of this room. */
    data object TargetNotAMember : TransferHostOutcome

    /** No such room. */
    data object RoomNotFound : TransferHostOutcome
}

/**
 * Result of [CampfireRegistry.setControlMode]. Like [TransferHostOutcome], host-only enforcement
 * is the service layer's job — this call unconditionally applies the mode change.
 */
sealed interface SetControlModeOutcome {
    /** The control mode changed; [frame] is what was broadcast. */
    data class Applied(
        val frame: CampfireFrame.ControlModeChanged,
    ) : SetControlModeOutcome

    /** No such room. */
    data object RoomNotFound : SetControlModeOutcome
}

/**
 * Result of [CampfireRegistry.sendReaction]. Per the co-listening design spec §5, a rate-limited
 * reaction is dropped **silently** — [Dropped] is not an error; the service layer (Task 3) should
 * treat it exactly like [Sent] from the caller's perspective (no error surfaces to the sender).
 */
sealed interface ReactionOutcome {
    /** The reaction was broadcast. */
    data object Sent : ReactionOutcome

    /** The caller's per-member burst limit was exceeded; nothing was broadcast. */
    data object Dropped : ReactionOutcome

    /** The caller is not a member of this room. */
    data object NotAMember : ReactionOutcome

    /** No such room. */
    data object RoomNotFound : ReactionOutcome
}

/**
 * The live registry of in-memory campfire (co-listening) rooms — the §3.1/§3.5 ephemeral,
 * single-process presence pattern (`ActiveSessionRepository`'s sibling for shared playback, not
 * a `SyncEngine` domain). No table backs this: every room, its membership, chat ring, and
 * reaction counters live only in this process's heap, and a restart wipes all of them —
 * accepted honestly per the design spec, not treated as a gap to paper over.
 *
 * A registry-level [Mutex] serializes structural changes to the room map (create/remove); each
 * [CampfireRoom] then serializes its own state under its own `Mutex`, so unrelated rooms never
 * contend with each other. `CampfireServiceImpl` (Task 3) is the only intended caller — it owns
 * book-access gating, host-only enforcement, and `AppError` translation; this class knows
 * nothing about either.
 *
 * Every method accepts an optional `now` override — production callers rely on the injected
 * [clock], tests pass an explicit [Instant] (or inject a fixed [clock]) for deterministic time.
 */
class CampfireRegistry(
    private val clock: Clock = Clock.System,
    private val maxMembers: Int = DEFAULT_MAX_MEMBERS,
    private val awayGrace: Duration = DEFAULT_AWAY_GRACE,
    private val idleTimeout: Duration = DEFAULT_IDLE_TIMEOUT,
    private val chatRingSize: Int = DEFAULT_CHAT_RING_SIZE,
    private val reactionBurstLimit: Int = DEFAULT_REACTION_BURST_LIMIT,
    private val reactionBurstWindow: Duration = DEFAULT_REACTION_BURST_WINDOW,
) {
    private val roomsLock = Mutex()
    private val rooms = LinkedHashMap<CampfireId, CampfireRoom>()

    /** Creates a new room with [hostUserId] as host and the sole initial member. */
    suspend fun createRoom(
        id: CampfireId,
        bookId: String,
        hostUserId: String,
        hostDisplayName: String?,
        settings: CampfireSettings,
        startingPositionMs: Long = 0L,
        now: Instant = clock.now(),
    ): CampfireSnapshot {
        val room =
            CampfireRoom(
                id = id,
                bookId = bookId,
                settings = settings,
                hostUserId = hostUserId,
                hostDisplayName = hostDisplayName,
                startingPositionMs = startingPositionMs,
                now = now,
                maxMembers = maxMembers,
                chatRingSize = chatRingSize,
                reactionBurstLimit = reactionBurstLimit,
                reactionBurstWindow = reactionBurstWindow,
            )
        roomsLock.withLock { rooms[id] = room }
        log.info { "Campfire created id=${id.value} book=$bookId host=$hostUserId" }
        return room.snapshot()
    }

    /** The room's current state, or `null` if it doesn't exist. */
    suspend fun snapshot(roomId: CampfireId): CampfireSnapshot? = findRoom(roomId)?.snapshot()

    /** The room's live frame flow, or `null` if it doesn't exist. */
    suspend fun observe(roomId: CampfireId): SharedFlow<CampfireFrame>? = findRoom(roomId)?.frames

    /** See [JoinOutcome]. */
    suspend fun join(
        roomId: CampfireId,
        userId: String,
        displayName: String?,
        now: Instant = clock.now(),
    ): JoinOutcome = findRoom(roomId)?.join(userId, displayName, now) ?: JoinOutcome.RoomNotFound

    /** See [LeaveOutcome]. Removes the room from the registry when it reports [LeaveOutcome.RoomEnded]. */
    suspend fun leave(
        roomId: CampfireId,
        userId: String,
        now: Instant = clock.now(),
    ): LeaveOutcome {
        val room = findRoom(roomId) ?: return LeaveOutcome.RoomNotFound
        val outcome = room.leave(userId, now)
        if (outcome is LeaveOutcome.RoomEnded) forget(roomId)
        return outcome
    }

    /** Marks [userId] away in [roomId] (flow disconnect). Silent no-op if the room or member doesn't exist. */
    suspend fun markAway(
        roomId: CampfireId,
        userId: String,
        now: Instant = clock.now(),
    ) {
        findRoom(roomId)?.markAway(userId, now)
    }

    /** Clears [userId]'s away flag in [roomId] (a reconnect within the grace window). */
    suspend fun clearAway(
        roomId: CampfireId,
        userId: String,
        now: Instant = clock.now(),
    ) {
        findRoom(roomId)?.clearAway(userId, now)
    }

    /** Ends [roomId] for everyone with [reason] and removes it from the registry. Silent no-op if it doesn't exist. */
    suspend fun endSession(
        roomId: CampfireId,
        reason: String = CAMPFIRE_END_REASON_HOST_ENDED,
        now: Instant = clock.now(),
    ) {
        val room = findRoom(roomId) ?: return
        room.end(now, reason)
        forget(roomId)
    }

    /** See [TransferHostOutcome]. */
    suspend fun transferHost(
        roomId: CampfireId,
        toUserId: String,
        now: Instant = clock.now(),
    ): TransferHostOutcome = findRoom(roomId)?.transferHost(toUserId, now) ?: TransferHostOutcome.RoomNotFound

    /** See [SetControlModeOutcome]. */
    suspend fun setControlMode(
        roomId: CampfireId,
        mode: CampfireControlMode,
        now: Instant = clock.now(),
    ): SetControlModeOutcome = findRoom(roomId)?.setControlMode(mode, now) ?: SetControlModeOutcome.RoomNotFound

    /** See [CommandOutcome]. */
    suspend fun applyCommand(
        roomId: CampfireId,
        userId: String,
        command: PlaybackCommand,
        now: Instant = clock.now(),
    ): CommandOutcome = findRoom(roomId)?.applyCommand(userId, command, now) ?: CommandOutcome.RoomNotFound

    /** See [ChatOutcome]. */
    suspend fun sendChat(
        roomId: CampfireId,
        userId: String,
        text: String,
        now: Instant = clock.now(),
    ): ChatOutcome = findRoom(roomId)?.sendChat(userId, text, now) ?: ChatOutcome.RoomNotFound

    /** See [ReactionOutcome]. */
    suspend fun sendReaction(
        roomId: CampfireId,
        userId: String,
        emoji: String,
        now: Instant = clock.now(),
    ): ReactionOutcome = findRoom(roomId)?.sendReaction(userId, emoji, now) ?: ReactionOutcome.RoomNotFound

    /**
     * Evicts every away-past-grace member across all rooms, ending any room that empties out as
     * a result. Pure and clock-driven (fake-clock testable); production scheduling is Task 3/4's
     * concern (the `ActiveSessionCleanupTask` periodic-sweep pattern). Returns the ids of rooms
     * that ended as a consequence.
     */
    suspend fun reapAwayMembers(now: Instant = clock.now()): List<CampfireId> {
        val snapshot = roomsLock.withLock { rooms.values.toList() }
        val endedIds = snapshot.filter { it.reapAwayMembers(now, awayGrace) }.map { it.id }
        if (endedIds.isNotEmpty()) {
            roomsLock.withLock { endedIds.forEach { rooms.remove(it) } }
            log.info { "Campfire away-grace reap ended ${endedIds.size} room(s)" }
        }
        return endedIds
    }

    /**
     * Ends every room with no join/leave/command/chat/reaction activity in the last [idleTimeout]
     * — the §4 idle sweeper. Pure and clock-driven; production scheduling is Task 3/4's concern.
     * Returns the ids of rooms it ended.
     */
    suspend fun reapIdle(now: Instant = clock.now()): List<CampfireId> {
        val idleIds =
            roomsLock.withLock {
                rooms.values.filter { (now - it.lastActivityAt) >= idleTimeout }.map { it.id }
            }
        for (id in idleIds) {
            findRoom(id)?.end(now, CAMPFIRE_END_REASON_IDLE)
        }
        if (idleIds.isNotEmpty()) {
            roomsLock.withLock { idleIds.forEach { rooms.remove(it) } }
            log.info { "Campfire idle reap ended ${idleIds.size} room(s)" }
        }
        return idleIds
    }

    private suspend fun findRoom(roomId: CampfireId): CampfireRoom? = roomsLock.withLock { rooms[roomId] }

    private suspend fun forget(roomId: CampfireId) {
        roomsLock.withLock { rooms.remove(roomId) }
    }
}
