@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.CampfireService
import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.campfire.CampfireControlMode
import com.calypsan.listenup.api.dto.campfire.CampfireFrame
import com.calypsan.listenup.api.dto.campfire.CampfireId
import com.calypsan.listenup.api.dto.campfire.CampfireSettings
import com.calypsan.listenup.api.dto.campfire.CampfireSnapshot
import com.calypsan.listenup.api.dto.campfire.OpenCampfireSummary
import com.calypsan.listenup.api.dto.campfire.PlaybackCommand
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.CampfireError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.campfire.CampfireRegistry
import com.calypsan.listenup.server.campfire.ChatOutcome
import com.calypsan.listenup.server.campfire.CommandOutcome
import com.calypsan.listenup.server.campfire.JoinOutcome
import com.calypsan.listenup.server.campfire.LeaveOutcome
import com.calypsan.listenup.server.campfire.ReactionOutcome
import com.calypsan.listenup.server.campfire.SetControlModeOutcome
import com.calypsan.listenup.server.campfire.TransferHostOutcome
import com.calypsan.listenup.server.campfire.posAt
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.services.PlaybackPositionRepository
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
 * Route handlers call [copyWith] to bind each request to the authenticated principal; the Koin
 * singleton carries an unscoped placeholder [PrincipalProvider] that throws (fail-loud) if ever
 * invoked, so a route that forgets to [copyWith] surfaces as a guarded `InternalError` rather than
 * silently leaking unscoped data.
 */
internal class CampfireServiceImpl(
    private val registry: CampfireRegistry,
    private val bookAccessPolicy: BookAccessPolicy,
    private val playbackPositions: PlaybackPositionRepository,
    private val publicProfiles: PublicProfileRepository,
    private val db: ListenUpDatabase,
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
        // The creator's own progress IS the anchor — never a spoiler to themselves.
        return AppResult.Success(snapshot.copy(yourPositionMs = startingPositionMs, spoilerAhead = false))
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
        return when (registry.leave(sessionId, caller.userId)) {
            is LeaveOutcome.Left, LeaveOutcome.RoomEnded -> AppResult.Success(Unit)
            LeaveOutcome.NotAMember -> AppResult.Failure(CampfireError.NotAMember())
            LeaveOutcome.RoomNotFound -> AppResult.Failure(CampfireError.CampfireNotFound())
        }
    }

    override suspend fun endSession(sessionId: CampfireId): AppResult<Unit> {
        val caller = resolveCaller() ?: return noPrincipal()
        val snapshot = registry.snapshot(sessionId) ?: return AppResult.Failure(CampfireError.CampfireNotFound())
        if (snapshot.hostUserId != caller.userId) return AppResult.Failure(CampfireError.NotController())
        registry.endSession(sessionId)
        return AppResult.Success(Unit)
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

    override suspend fun listOpenSessions(): AppResult<List<OpenCampfireSummary>> {
        resolveCaller() ?: return noPrincipal()
        // ACL-filtered discovery lands with the CampfiresChanged nudge (Task 4) — the registry
        // doesn't yet expose a room-listing surface. Returning none here is an honest "nothing to
        // discover yet", not a silent gap: Task 4 wires this fully.
        return AppResult.Success(emptyList())
    }

    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): CampfireServiceImpl =
        CampfireServiceImpl(
            registry = registry,
            bookAccessPolicy = bookAccessPolicy,
            playbackPositions = playbackPositions,
            publicProfiles = publicProfiles,
            db = db,
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
        val aheadByTime = (roomPositionMs - yourPos) > SPOILER_TIME_THRESHOLD_MS
        val aheadByChapter = (chapterIndexAt(bookId, roomPositionMs) - chapterIndexAt(bookId, yourPos)) > SPOILER_CHAPTER_THRESHOLD
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

    private suspend fun displayNameOf(userId: String): String? = publicProfiles.identities(setOf(userId))[userId]?.displayName

    /** The resolved caller: their user id and contract role (the role [BookAccessPolicy] speaks). */
    private data class Caller(
        val userId: String,
        val role: UserRole,
    )

    private fun resolveCaller(): Caller? = principal.current()?.let { Caller(it.userId.value, it.role) }

    private fun noPrincipal(): AppResult.Failure = AppResult.Failure(AuthError.PermissionDenied())
}
