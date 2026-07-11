@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.CampfireService
import com.calypsan.listenup.api.dto.activity.ActivityType
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireFrame
import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.CampfireInvitableUser
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.api.dto.campfire.CampfireSnapshot
import com.calypsan.listenup.api.dto.campfire.OpenCampfireSummary
import com.calypsan.listenup.api.dto.campfire.PlaybackCommand
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.CampfireError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserRoleLookup
import com.calypsan.listenup.server.auth.toContract
import com.calypsan.listenup.server.campfire.CampfireEndInfo
import com.calypsan.listenup.server.campfire.CampfireInviteNotifier
import com.calypsan.listenup.server.campfire.CampfireRegistry
import com.calypsan.listenup.server.campfire.ChatOutcome
import com.calypsan.listenup.server.campfire.CommandOutcome
import com.calypsan.listenup.server.campfire.JoinOutcome
import com.calypsan.listenup.server.campfire.LeaveOutcome
import com.calypsan.listenup.server.campfire.ReactionOutcome
import com.calypsan.listenup.server.campfire.SetControlModeOutcome
import com.calypsan.listenup.server.campfire.TransferHostOutcome
import com.calypsan.listenup.server.campfire.posAt
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.services.ActivityRecorder
import com.calypsan.listenup.server.services.PlaybackPositionRepository
import com.calypsan.listenup.server.sync.ChangeBus
import com.calypsan.listenup.server.sync.PublicProfileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

/** The §7 threshold: a room more than this far past the caller's own position is a spoiler. */
private val SPOILER_TIME_THRESHOLD_MS = 10.minutes.inWholeMilliseconds

/** The §7 threshold: a room more than this many chapters past the caller's own is a spoiler. */
private const val SPOILER_CHAPTER_THRESHOLD = 1

/**
 * [CampfireService] implementation — the access + control gate in front of the pure, in-memory
 * [CampfireRegistry]. Every fallible method resolves the caller from [principal] (never from
 * request fields, the [SocialServiceImpl] idiom) and gates:
 *
 * - **Book access** ([com.calypsan.listenup.server.api.BookAccessPolicy.canAccess]) on
 *   [createSession] (`BookAccessDenied` on failure — the caller named the book themselves) and on
 *   [joinSession] (`CampfireNotFound` on failure — the deny-shape for every other room-access
 *   failure, so an invite-only or inaccessible room never reveals its existence).
 * - **Membership** ([CampfireRegistry]'s own `NotAMember`/`RoomNotFound` outcomes) on
 *   [sendCommand]/[sendChat]/[sendReaction]/[leaveSession], and explicitly in [observeSession]
 *   since that method has no `AppResult` to fail through.
 * - **Host-only control** — [transferHost], [endSession], and [setControlMode] read the room's
 *   current [CampfireSnapshot.hostUserId] and reject a non-host caller with `NotController`
 *   *before* calling the registry, which performs the mutation unconditionally (the registry has
 *   no concept of "controller" — see [TransferHostOutcome]/[SetControlModeOutcome] KDoc). The same
 *   pattern gates [sendCommand] in [CampfireControlMode.HOST_ONLY] rooms.
 *
 * **Invites and the "listened together" activity (Task 5).** [createSession] push-notifies every
 * invited user who can access the book via [CampfireInviteNotifier] (best-effort; push is an
 * accelerant, never a requirement — the room stays discoverable via [listOpenSessions]
 * regardless). [endSession] and a [leaveSession] that empties the room both record one
 * [ActivityType.CAMPFIRE_TOGETHER] activity when the ending [CampfireEndInfo] shows at least 2
 * distinct all-time participants — see [recordTogetherActivity].
 *
 * Route handlers call [copyWith] to bind each request to the authenticated principal; the Koin
 * singleton carries an unscoped placeholder [PrincipalProvider] that throws (fail-loud) if ever
 * invoked, so a route that forgets to [copyWith] surfaces as a guarded `InternalError` rather than
 * silently leaking unscoped data.
 *
 * **Discoverability nudges.** [CampfireRegistry] deliberately knows nothing about
 * [ChangeBus] (its own class KDoc) — every transition that changes what
 * [listOpenSessions] can return is nudged from here instead, via
 * `bus.broadcastControl(SyncControl.CampfiresChanged)`: a room's [createSession] (it becomes
 * discoverable), and a room ending via [endSession] or a [leaveSession] that empties it
 * ([LeaveOutcome.RoomEnded]) (it stops being discoverable). Reaper-driven endings (away-grace
 * eviction down to empty, idle sweep) nudge from [com.calypsan.listenup.server.scheduler.CampfireReaperTask]
 * instead, since those transitions never pass through this service. A room merely filling to (or
 * draining from) its member cap does NOT nudge — capacity doesn't change discoverability; a full
 * room still appears in the listing (as full).
 */
internal class CampfireServiceImpl(
    private val registry: CampfireRegistry,
    private val bookAccessPolicy: BookAccessPolicy,
    private val playbackPositions: PlaybackPositionRepository,
    private val publicProfiles: PublicProfileRepository,
    private val db: ListenUpDatabase,
    private val bus: ChangeBus,
    private val userRoleLookup: UserRoleLookup,
    private val inviteNotifier: CampfireInviteNotifier,
    private val activityRecorder: ActivityRecorder,
    private val clock: Clock = Clock.System,
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : CampfireService {
    override suspend fun createSession(
        bookId: String,
        settings: CampfireSettings,
    ): AppResult<CampfireSnapshot> {
        val caller = resolveCaller() ?: return noPrincipal()
        if (!bookAccessPolicy.canAccess(caller.userId, caller.role, bookId)) {
            return AppResult.Failure(CampfireError.BookAccessDenied())
        }
        // The starting anchor is the creator's own stored position (paused) — the contract's
        // createSession takes no position param, so there is nothing else to anchor from.
        val startingPositionMs = playbackPositions.getPosition(caller.userId, bookId)?.positionMs ?: 0L
        val displayName = displayNameOf(caller.userId)
        val snapshot =
            registry.createRoom(
                id = CampfireId(Uuid.random().toString()),
                bookId = bookId,
                hostUserId = caller.userId,
                hostDisplayName = displayName,
                settings = settings,
                startingPositionMs = startingPositionMs,
            )
        // A new room is now discoverable (subject to listOpenSessions' own ACL/invite filtering).
        bus.broadcastControl(SyncControl.CampfiresChanged)
        notifyInvitedMembers(snapshot.id, bookId, caller.userId, settings.invitedUserIds)
        // The creator's own progress IS the anchor — never a spoiler to themselves.
        return AppResult.Success(snapshot.copy(yourPositionMs = startingPositionMs, spoilerAhead = false))
    }

    /**
     * Push-notifies every invited user who can actually access [bookId] — an invite to a book
     * the recipient can't see would be a dead-end deep link (design spec §7), and never revealing
     * that a campfire exists for an inaccessible book is the same privacy posture [joinSession]
     * already enforces via the `CampfireNotFound` deny-shape. The filter runs BEFORE
     * [CampfireInviteNotifier] ever sees the invitee, so an inaccessible invite never reaches
     * [com.calypsan.listenup.server.push.PushNotifier] at all. Never notifies [inviterUserId].
     */
    private suspend fun notifyInvitedMembers(
        campfireId: CampfireId,
        bookId: String,
        inviterUserId: String,
        invitedUserIds: List<String>,
    ) {
        if (invitedUserIds.isEmpty()) return
        val reachable =
            invitedUserIds.filter { userId ->
                userId != inviterUserId &&
                    bookAccessPolicy.canAccess(userId, userRoleLookup.roleOf(userId) ?: UserRole.MEMBER, bookId)
            }
        if (reachable.isNotEmpty()) {
            inviteNotifier.notifyInvited(campfireId, bookId, inviterUserId, reachable)
        }
    }

    override suspend fun joinSession(sessionId: CampfireId): AppResult<CampfireSnapshot> {
        val caller = resolveCaller() ?: return noPrincipal()
        val existing = registry.snapshot(sessionId) ?: return AppResult.Failure(CampfireError.CampfireNotFound())
        // Inaccessible book → the same CampfireNotFound deny-shape as a genuinely missing room —
        // never revealing that a room for this book exists (the SocialServiceImpl precedent).
        if (!bookAccessPolicy.canAccess(caller.userId, caller.role, existing.bookId)) {
            return AppResult.Failure(CampfireError.CampfireNotFound())
        }
        val displayName = displayNameOf(caller.userId)
        return when (val outcome = registry.join(sessionId, caller.userId, displayName)) {
            is JoinOutcome.Joined -> AppResult.Success(scopedToCaller(outcome.snapshot, caller.userId))
            JoinOutcome.RoomFull -> AppResult.Failure(CampfireError.CampfireFull())
            JoinOutcome.RoomNotFound -> AppResult.Failure(CampfireError.CampfireNotFound())
        }
    }

    override suspend fun leaveSession(sessionId: CampfireId): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        return when (val outcome = registry.leave(sessionId, caller.userId)) {
            is LeaveOutcome.Left -> {
                AppResult.Success(Unit)
            }

            is LeaveOutcome.RoomEnded -> {
                // The last member leaving ended the room — it stops being discoverable.
                bus.broadcastControl(SyncControl.CampfiresChanged)
                recordTogetherActivity(outcome.endInfo)
                AppResult.Success(Unit)
            }

            LeaveOutcome.NotAMember -> {
                AppResult.Failure(CampfireError.NotAMember())
            }

            LeaveOutcome.RoomNotFound -> {
                AppResult.Failure(CampfireError.CampfireNotFound())
            }
        }
    }

    override suspend fun endSession(sessionId: CampfireId): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        val snapshot = registry.snapshot(sessionId) ?: return AppResult.Failure(CampfireError.CampfireNotFound())
        if (snapshot.hostUserId != caller.userId) return AppResult.Failure(CampfireError.NotController())
        val endInfo = registry.endSession(sessionId)
        // The room stops being discoverable.
        bus.broadcastControl(SyncControl.CampfiresChanged)
        recordTogetherActivity(endInfo)
        return AppResult.Success(Unit)
    }

    /**
     * Records the design spec §7 "listened together" activity when [endInfo] shows at least 2
     * distinct all-time participants ([CampfireEndInfo.participantUserIds] — actual joiners only,
     * never merely-invited users who never joined). Attributed to the host at the moment the room
     * ended (after any [transferHost] handoffs). A solo session (only ever the host) never records.
     */
    private suspend fun recordTogetherActivity(endInfo: CampfireEndInfo?) {
        if (endInfo == null || endInfo.participantUserIds.size < 2) return
        activityRecorder.record(
            userId = endInfo.hostUserId,
            type = ActivityType.CAMPFIRE_TOGETHER,
            bookId = endInfo.bookId,
        )
    }

    override suspend fun transferHost(
        sessionId: CampfireId,
        toUserId: String,
    ): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        val snapshot = registry.snapshot(sessionId) ?: return AppResult.Failure(CampfireError.CampfireNotFound())
        if (snapshot.hostUserId != caller.userId) return AppResult.Failure(CampfireError.NotController())
        return when (registry.transferHost(sessionId, toUserId)) {
            is TransferHostOutcome.Transferred -> AppResult.Success(Unit)

            // Reuses NotAMember for "the transfer target isn't a member" — the family has no
            // dedicated third-party-target error and this is the closest fit (see class KDoc).
            TransferHostOutcome.TargetNotAMember -> AppResult.Failure(CampfireError.NotAMember())

            TransferHostOutcome.RoomNotFound -> AppResult.Failure(CampfireError.CampfireNotFound())
        }
    }

    override suspend fun setControlMode(
        sessionId: CampfireId,
        mode: CampfireControlMode,
    ): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        val snapshot = registry.snapshot(sessionId) ?: return AppResult.Failure(CampfireError.CampfireNotFound())
        if (snapshot.hostUserId != caller.userId) return AppResult.Failure(CampfireError.NotController())
        return when (registry.setControlMode(sessionId, mode)) {
            is SetControlModeOutcome.Applied -> AppResult.Success(Unit)
            SetControlModeOutcome.RoomNotFound -> AppResult.Failure(CampfireError.CampfireNotFound())
        }
    }

    /**
     * The live downlink. Flow collection IS presence (class KDoc + [CampfireService] KDoc):
     *
     * 1. Verify the caller is a member before streaming anything — a bad [sessionId] or a caller
     *    who never joined gets a single [RpcEvent.Error] frame, then the flow completes (there is
     *    no `AppResult` to fail through on a `Flow`-returning method, so the error rides the same
     *    envelope every frame does — matching the drift note's guidance to fail this way rather
     *    than emit an empty flow, since the caller needs to know *why* nothing is coming).
     * 2. On successful collection start, clear the caller's away flag (a reconnect within grace).
     * 3. On collection end — normal completion, cancellation, or a dropped socket — mark the caller
     *    away; [CampfireReaperTask] evicts them once the grace window elapses.
     */
    override fun observeSession(sessionId: CampfireId): Flow<RpcEvent<CampfireFrame>> {
        val caller = resolveCaller()
        return flow {
            if (caller == null) {
                emit(RpcEvent.Error(CampfireError.NotAMember()))
                return@flow
            }
            val snapshot = registry.snapshot(sessionId)
            if (snapshot == null) {
                emit(RpcEvent.Error(CampfireError.CampfireNotFound()))
                return@flow
            }
            if (snapshot.members.none { it.userId == caller.userId }) {
                emit(RpcEvent.Error(CampfireError.NotAMember()))
                return@flow
            }
            registry.clearAway(sessionId, caller.userId)
            val frames = registry.observe(sessionId)
            if (frames == null) {
                emit(RpcEvent.Error(CampfireError.CampfireNotFound()))
                return@flow
            }
            emitAll(frames.map { RpcEvent.Data(it) })
        }.onCompletion {
            if (caller != null) registry.markAway(sessionId, caller.userId)
        }
    }

    override suspend fun sendCommand(
        sessionId: CampfireId,
        command: PlaybackCommand,
    ): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        val snapshot = registry.snapshot(sessionId) ?: return AppResult.Failure(CampfireError.CampfireNotFound())
        if (snapshot.members.none { it.userId == caller.userId }) {
            return AppResult.Failure(CampfireError.NotAMember())
        }
        if (snapshot.settings.controlMode == CampfireControlMode.HOST_ONLY && snapshot.hostUserId != caller.userId) {
            return AppResult.Failure(CampfireError.NotController())
        }
        return when (registry.applyCommand(sessionId, caller.userId, command)) {
            // Applied/NoOp/Rejected are all "the command was accepted" from the caller's
            // perspective (design spec §9: "no conflict UI exists or is needed") — a losing
            // simultaneous command silently doesn't apply; the sender notices via the next frame.
            is CommandOutcome.Applied, CommandOutcome.NoOp, CommandOutcome.Rejected -> AppResult.Success(Unit)

            CommandOutcome.NotAMember -> AppResult.Failure(CampfireError.NotAMember())

            CommandOutcome.RoomNotFound -> AppResult.Failure(CampfireError.CampfireNotFound())
        }
    }

    override suspend fun sendChat(
        sessionId: CampfireId,
        text: String,
    ): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        return when (registry.sendChat(sessionId, caller.userId, text)) {
            is ChatOutcome.Sent -> AppResult.Success(Unit)
            ChatOutcome.NotAMember -> AppResult.Failure(CampfireError.NotAMember())
            ChatOutcome.RoomNotFound -> AppResult.Failure(CampfireError.CampfireNotFound())
        }
    }

    override suspend fun sendReaction(
        sessionId: CampfireId,
        emoji: String,
    ): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        return when (registry.sendReaction(sessionId, caller.userId, emoji)) {
            // A rate-limited reaction is dropped silently per the registry's own contract — no
            // error surfaces to the sender (see ReactionOutcome KDoc).
            ReactionOutcome.Sent, ReactionOutcome.Dropped -> AppResult.Success(Unit)

            ReactionOutcome.NotAMember -> AppResult.Failure(CampfireError.NotAMember())

            ReactionOutcome.RoomNotFound -> AppResult.Failure(CampfireError.CampfireNotFound())
        }
    }

    /**
     * ACL-filtered discovery listing — the [SocialServiceImpl] precedent: read every live room off
     * [CampfireRegistry], then keep only those the caller may see.
     *
     * - **Book access**: [BookAccessPolicy.accessibleBookIds] returns `null` for ROOT/ADMIN
     *   (unconstrained — every room visible); everyone else is filtered to their accessible set.
     * - **Invite-only exclusion**: a room with [CampfireSettings.inviteOnly] set is dropped unless
     *   the caller is already a member or is named in [CampfireSettings.invitedUserIds] — Task 5
     *   finishes invite semantics (accept/decline, push), but the exclusion rule lands here so an
     *   invite-only room never leaks to a stranger via discovery.
     * - **Capacity**: a full room is still returned (at its full [OpenCampfireSummary.memberCount])
     *   — capacity doesn't affect discoverability, only whether [sendCommand]/[joinSession] will
     *   accept a new member.
     */
    override suspend fun listOpenSessions(): AppResult<List<OpenCampfireSummary>> {
        val caller = resolveCaller() ?: return noPrincipal()
        val accessibleBookIds = bookAccessPolicy.accessibleBookIds(caller.userId, caller.role)
        val visible =
            registry.listSnapshots().filter { room ->
                (accessibleBookIds == null || room.bookId in accessibleBookIds) &&
                    (!room.settings.inviteOnly || isInvitedOrMember(room, caller.userId))
            }
        return AppResult.Success(
            visible.map { room ->
                OpenCampfireSummary(
                    id = room.id,
                    bookId = room.bookId,
                    hostUserId = room.hostUserId,
                    memberCount = room.members.size,
                    controlMode = room.settings.controlMode,
                    inviteOnly = room.settings.inviteOnly,
                )
            },
        )
    }

    /**
     * The create/invite sheet's user picker: every live ACTIVE user (other than the caller) who
     * can already see [bookId] — the design spec §7 "no dead-end invites" rule. Filtering happens
     * entirely server-side so a member's book-access boundary is never exposed by handing the
     * client the full user roster just to build an invite list.
     */
    override suspend fun listInvitableUsers(bookId: String): AppResult<List<CampfireInvitableUser>> {
        val caller = resolveCaller() ?: return noPrincipal()
        val liveUsers = suspendTransaction(db) { db.usersQueries.selectActiveLive().executeAsList() }
        val invitable =
            liveUsers
                .filter { it.id != caller.userId }
                .filter { bookAccessPolicy.canAccess(it.id, UserRoleColumn.valueOf(it.role).toContract(), bookId) }
        return AppResult.Success(invitable.map { CampfireInvitableUser(userId = it.id, displayName = it.display_name) })
    }

    /** True when [userId] is already a member of [room], or was explicitly invited to it. */
    private fun isInvitedOrMember(
        room: CampfireSnapshot,
        userId: String,
    ): Boolean = userId in room.settings.invitedUserIds || room.members.any { it.userId == userId }

    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): CampfireServiceImpl =
        CampfireServiceImpl(
            registry = registry,
            bookAccessPolicy = bookAccessPolicy,
            playbackPositions = playbackPositions,
            publicProfiles = publicProfiles,
            db = db,
            bus = bus,
            userRoleLookup = userRoleLookup,
            inviteNotifier = inviteNotifier,
            activityRecorder = activityRecorder,
            clock = clock,
            principal = principal,
        )

    /** [snapshot] with [CampfireSnapshot.yourPositionMs]/[CampfireSnapshot.spoilerAhead] resolved for [userId]. */
    private suspend fun scopedToCaller(
        snapshot: CampfireSnapshot,
        userId: String,
    ): CampfireSnapshot {
        val yourPositionMs = playbackPositions.getPosition(userId, snapshot.bookId)?.positionMs
        val roomPositionMs = snapshot.anchor.posAt(clock.now())
        val spoiler = computeSpoilerAhead(snapshot.bookId, roomPositionMs, yourPositionMs)
        return snapshot.copy(yourPositionMs = yourPositionMs, spoilerAhead = spoiler)
    }

    /**
     * The §7 spoiler-ahead check: true when the room is ahead of the caller's own furthest
     * position (never played = position 0) by more than [SPOILER_TIME_THRESHOLD_MS] or by more
     * than [SPOILER_CHAPTER_THRESHOLD] chapters. Never true when the caller is at or ahead of the
     * room.
     */
    private suspend fun computeSpoilerAhead(
        bookId: String,
        roomPositionMs: Long,
        yourPositionMs: Long?,
    ): Boolean {
        val yourPos = yourPositionMs ?: 0L
        if (roomPositionMs <= yourPos) return false
        val aheadByTime = roomPositionMs - yourPos > SPOILER_TIME_THRESHOLD_MS
        val aheadByChapter =
            chapterIndexAt(bookId, roomPositionMs) - chapterIndexAt(bookId, yourPos) > SPOILER_CHAPTER_THRESHOLD
        return aheadByTime || aheadByChapter
    }

    /** The ordinal of the chapter containing [positionMs], or `0` for a chapterless book. */
    private suspend fun chapterIndexAt(
        bookId: String,
        positionMs: Long,
    ): Int {
        val chapters =
            suspendTransaction(db) {
                db.bookChaptersQueries.selectByBookIds(listOf(bookId)).executeAsList()
            }
        if (chapters.isEmpty()) return 0
        return chapters
            .sortedBy { it.ordinal }
            .indexOfLast { it.start_time <= positionMs }
            .coerceAtLeast(0)
    }

    private suspend fun displayNameOf(userId: String): String? =
        publicProfiles.identities(setOf(userId))[userId]?.displayName

    /** The resolved caller: their user id and contract role (the role [BookAccessPolicy] speaks). */
    private data class Caller(
        val userId: String,
        val role: UserRole,
    )

    private fun resolveCaller(): Caller? = principal.current()?.let { Caller(it.userId.value, it.role) }

    private fun noPrincipal(): AppResult.Failure = AppResult.Failure(AuthError.PermissionDenied())
}
