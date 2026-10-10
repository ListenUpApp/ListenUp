package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.WorldEventService
import com.calypsan.listenup.api.dto.auth.Permission
import com.calypsan.listenup.api.dto.worldevent.EventsBatch
import com.calypsan.listenup.api.dto.worldevent.WorldEventChange
import com.calypsan.listenup.api.dto.worldevent.WorldEventOp
import com.calypsan.listenup.api.dto.worldevent.WorldEventUpsert
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.error.WorldEventError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.WorldEventSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.StoryWorldHistoryId
import com.calypsan.listenup.core.WorldEventId
import com.calypsan.listenup.domain.storyworld.WorldEventRules
import com.calypsan.listenup.server.auth.PermissionPolicy
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.sync.WorldEventRepository

/**
 * [WorldEventService]: permission from [permissionPolicy] (contribute for every write and revert; ROOT/ADMIN
 * implicit), visibility from [accessPolicy] (a member needs to see the event's home and, when it is anchored,
 * its anchor book). An event out of sight answers [WorldEventError.NotFound] for writes and history and is left
 * out of listings — existence never leaks. The repository re-decides every integrity rule inside its
 * transaction. Route handlers bind the caller per request via [copyWith].
 */
internal class WorldEventServiceImpl(
    private val eventRepo: WorldEventRepository,
    private val permissionPolicy: PermissionPolicy,
    private val accessPolicy: BookAccessPolicy,
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : WorldEventService {
    /** A copy bound to [principal]; route handlers call this per request. */
    fun copyWith(principal: PrincipalProvider): WorldEventServiceImpl =
        WorldEventServiceImpl(
            eventRepo = eventRepo,
            permissionPolicy = permissionPolicy,
            accessPolicy = accessPolicy,
            principal = principal,
        )

    override suspend fun applyBatch(batch: EventsBatch): AppResult<Unit> {
        val caller = principal.current() ?: return denied()
        permissionPolicy.require(caller, Permission.CONTRIBUTE_STORY_WORLD)?.let { return AppResult.Failure(it) }
        batchProblem(batch)?.let { return AppResult.Failure(it) }
        for (op in batch.ops) opProblem(caller, op)?.let { return AppResult.Failure(it) }
        return eventRepo.applyBatch(batch.ops.map { it.normalized() }, caller.userId)
    }

    override suspend fun listEventsForSeries(seriesId: SeriesId): AppResult<List<WorldEventSyncPayload>> {
        val caller = principal.current() ?: return denied()
        if (!accessPolicy.canAccessSeries(caller.userId.value, caller.role, seriesId.value)) return AppResult.Success(emptyList())
        return AppResult.Success(visibleTo(caller, eventRepo.listLiveForSeries(seriesId)))
    }

    override suspend fun listEventsForBook(bookId: BookId): AppResult<List<WorldEventSyncPayload>> {
        val caller = principal.current() ?: return denied()
        if (!accessPolicy.canAccess(caller.userId.value, caller.role, bookId.value)) return AppResult.Success(emptyList())
        // A book-homed event can only be anchored to its own book, which the caller can see.
        return AppResult.Success(eventRepo.listLiveForBook(bookId))
    }

    override suspend fun listEventsForEntity(entityId: EntityId): AppResult<List<WorldEventSyncPayload>> {
        val caller = principal.current() ?: return denied()
        return AppResult.Success(visibleTo(caller, eventRepo.listLiveMentioning(entityId)))
    }

    override suspend fun listHistory(eventId: WorldEventId): AppResult<List<WorldEventChange>> {
        val caller = principal.current() ?: return denied()
        eventRepo.findById(eventId)?.takeIf { canSee(caller, it) } ?: return AppResult.Failure(notFound(eventId))
        return AppResult.Success(eventRepo.listHistory(eventId))
    }

    /**
     * A change of an event the caller can't see — or whose restored anchor they can't see — is
     * [WorldEventError.HistoryNotFound], before any permission is consulted. Then contribute is required.
     */
    override suspend fun revert(changeId: StoryWorldHistoryId): AppResult<WorldEventChange> {
        val caller = principal.current() ?: return denied()
        val missing = AppResult.Failure(WorldEventError.HistoryNotFound(debugInfo = "change=${changeId.value}"))
        val change = eventRepo.findChange(changeId) ?: return missing
        val event = eventRepo.findById(change.eventId) ?: return missing
        if (!canSee(caller, event)) return missing
        val restoredAnchor = change.before?.bookId
        if (restoredAnchor != null && !accessPolicy.canAccess(caller.userId.value, caller.role, restoredAnchor)) return missing
        permissionPolicy.require(caller, Permission.CONTRIBUTE_STORY_WORLD)?.let { return AppResult.Failure(it) }
        return eventRepo.revert(changeId, caller.userId)
    }

    private fun batchProblem(batch: EventsBatch): ValidationError? =
        when {
            batch.ops.isEmpty() -> {
                ValidationError(message = "There's nothing to change.")
            }

            batch.ops.size > WorldEventRules.MAX_BATCH -> {
                ValidationError(message = "Send at most ${WorldEventRules.MAX_BATCH} changes at once.")
            }

            else -> {
                batch.ops
                    .filterIsInstance<WorldEventOp.Upsert>()
                    .firstNotNullOfOrNull { WorldEventRules.homeProblem(it.upsert.homeSeriesId, it.upsert.homeBookId) }
            }
        }

    private suspend fun opProblem(
        caller: UserPrincipal,
        op: WorldEventOp,
    ): AppError? =
        when (op) {
            is WorldEventOp.Delete -> {
                val visible = eventRepo.findById(op.id)?.takeIf { it.deletedAt == null && canSee(caller, it) }
                if (visible == null) notFound(op.id) else null
            }

            is WorldEventOp.Upsert -> {
                upsertProblem(caller, op.upsert)
            }
        }

    private suspend fun upsertProblem(
        caller: UserPrincipal,
        upsert: WorldEventUpsert,
    ): AppError? {
        val homeVisible =
            accessPolicy.canSeeEntityHome(caller.userId.value, caller.role, upsert.homeSeriesId?.value, upsert.homeBookId?.value)
        if (!homeVisible) return notFound(upsert.id)
        // A stored event out of sight answers NotFound before the repository's rules could say it exists.
        val existing = eventRepo.findById(upsert.id)
        if (existing != null && !canSee(caller, existing)) return notFound(upsert.id)
        val anchor = upsert.bookId?.value ?: return null
        return if (accessPolicy.canAccess(caller.userId.value, caller.role, anchor)) {
            null
        } else {
            WorldEventError.InvalidAnchor(debugInfo = "book=$anchor")
        }
    }

    /** [events] the caller can see, one visibility probe per distinct (home, anchor). */
    private suspend fun visibleTo(
        caller: UserPrincipal,
        events: List<WorldEventSyncPayload>,
    ): List<WorldEventSyncPayload> {
        val verdicts = mutableMapOf<Triple<String?, String?, String?>, Boolean>()
        return events.filter { event ->
            verdicts.getOrPut(Triple(event.homeSeriesId, event.homeBookId, event.bookId)) { canSee(caller, event) }
        }
    }

    private suspend fun canSee(
        caller: UserPrincipal,
        event: WorldEventSyncPayload,
    ): Boolean =
        accessPolicy.canSeeWorldEvent(
            userId = caller.userId.value,
            role = caller.role,
            homeSeriesId = event.homeSeriesId,
            homeBookId = event.homeBookId,
            anchorBookId = event.bookId,
        )

    private fun WorldEventOp.normalized(): WorldEventOp =
        when (this) {
            is WorldEventOp.Delete -> this
            is WorldEventOp.Upsert -> WorldEventOp.Upsert(upsert.copy(text = upsert.text.trim(), detail = upsert.detail?.trim()?.ifEmpty { null }))
        }

    private fun <T> denied(): AppResult<T> = AppResult.Failure(AuthError.PermissionDenied())

    private fun notFound(id: WorldEventId) = WorldEventError.NotFound(debugInfo = "event=${id.value}")
}
