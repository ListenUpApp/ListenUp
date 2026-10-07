package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.EntityService
import com.calypsan.listenup.api.dto.entity.EntityChange
import com.calypsan.listenup.api.dto.entity.EntityUpsert
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.EntityError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntityKind
import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.EntityId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.StoryWorldHistoryId
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.sync.EntityRepository
import kotlin.time.Clock

/**
 * [EntityService]: permissions from [permissionPolicy] (contribute for create/edit/revert, curate for
 * merge/delete, ROOT/ADMIN implicit), visibility from [accessPolicy] (a member needs to see the home).
 * A hidden home answers [EntityError.NotFound] for writes and history, and an empty list for listings —
 * existence never leaks. Route handlers bind the caller per request via [copyWith].
 */
internal class EntityServiceImpl(
    private val entityRepo: EntityRepository,
    private val permissionPolicy: UserPermissionPolicy,
    private val accessPolicy: BookAccessPolicy,
    private val principal: PrincipalProvider = PrincipalProvider.None,
    private val clock: Clock = Clock.System,
) : EntityService {
    /** A copy bound to [principal]; route handlers call this per request. */
    fun copyWith(principal: PrincipalProvider): EntityServiceImpl =
        EntityServiceImpl(entityRepo, permissionPolicy, accessPolicy, principal, clock)

    override suspend fun upsertEntity(upsert: EntityUpsert): AppResult<EntitySyncPayload> {
        val caller = principal.current() ?: return denied()
        permissionPolicy.requireCanContributeStoryWorld(caller.userId, caller.role)?.let { return AppResult.Failure(it) }
        validate(upsert)?.let { return AppResult.Failure(it) }
        if (!canSee(caller, upsert.homeSeriesId?.value, upsert.homeBookId?.value)) return notFound(upsert.id)
        val existing = entityRepo.findById(upsert.id)
        existingProblem(caller, upsert, existing)?.let { return AppResult.Failure(it) }
        val kind = if (upsert.kind == EntityKind.UNKNOWN) existing?.kind ?: EntityKind.UNKNOWN else upsert.kind
        // The repository stamps updated_at (and created_at on insert) with the server clock; these are placeholders.
        val now = clock.now().toEpochMilliseconds()
        val payload =
            EntitySyncPayload(
                id = upsert.id.value,
                kind = kind,
                name = upsert.name.trim(),
                descriptor = upsert.descriptor?.trim()?.ifEmpty { null },
                parentId = upsert.parentId?.value,
                homeSeriesId = upsert.homeSeriesId?.value,
                homeBookId = upsert.homeBookId?.value,
                imageRef = existing?.imageRef,
                createdBy = existing?.createdBy ?: caller.userId.value,
                updatedBy = caller.userId.value,
                revision = existing?.revision ?: 0L,
                updatedAt = now,
                createdAt = existing?.createdAt ?: now,
                deletedAt = null,
            )
        return entityRepo.upsertEntity(payload, caller.userId)
    }

    override suspend fun deleteEntity(id: EntityId): AppResult<Unit> {
        val caller = principal.current() ?: return denied()
        permissionPolicy.requireCanCurateStoryWorld(caller.userId, caller.role)?.let { return AppResult.Failure(it) }
        if (visibleLive(caller, id) == null) return notFound(id)
        return entityRepo.deleteEntity(id, caller.userId)
    }

    override suspend fun mergeEntities(
        source: EntityId,
        target: EntityId,
    ): AppResult<EntitySyncPayload> {
        val caller = principal.current() ?: return denied()
        permissionPolicy.requireCanCurateStoryWorld(caller.userId, caller.role)?.let { return AppResult.Failure(it) }
        if (visibleLive(caller, source) == null) return notFound(source)
        if (visibleLive(caller, target) == null) return notFound(target)
        return entityRepo.mergeEntities(source, target, caller.userId)
    }

    override suspend fun listEntitiesForSeries(seriesId: SeriesId): AppResult<List<EntitySyncPayload>> {
        val caller = principal.current() ?: return denied()
        if (!canSee(caller, seriesId.value, null)) return AppResult.Success(emptyList())
        return AppResult.Success(entityRepo.listLiveForSeries(seriesId))
    }

    override suspend fun listEntitiesForBook(bookId: BookId): AppResult<List<EntitySyncPayload>> {
        val caller = principal.current() ?: return denied()
        if (!canSee(caller, null, bookId.value)) return AppResult.Success(emptyList())
        return AppResult.Success(entityRepo.listLiveForBook(bookId))
    }

    override suspend fun listHistory(entityId: EntityId): AppResult<List<EntityChange>> {
        val caller = principal.current() ?: return denied()
        val entity = entityRepo.findById(entityId) ?: return notFound(entityId)
        if (!canSee(caller, entity.homeSeriesId, entity.homeBookId)) return notFound(entityId)
        return AppResult.Success(entityRepo.listHistory(entityId))
    }

    override suspend fun revert(changeId: StoryWorldHistoryId): AppResult<EntityChange> {
        val caller = principal.current() ?: return denied()
        permissionPolicy.requireCanContributeStoryWorld(caller.userId, caller.role)?.let { return AppResult.Failure(it) }
        val missing = AppResult.Failure(EntityError.HistoryNotFound(debugInfo = "change=${changeId.value}"))
        val change = entityRepo.findChange(changeId) ?: return missing
        val entity = entityRepo.findById(change.entityId) ?: return missing
        if (!canSee(caller, entity.homeSeriesId, entity.homeBookId)) return missing
        return entityRepo.revert(changeId, caller.userId)
    }

    /** Refusals that depend on the stored row: a hidden existing home, a changed home, an UNKNOWN create. */
    private suspend fun existingProblem(
        caller: UserPrincipal,
        upsert: EntityUpsert,
        existing: EntitySyncPayload?,
    ): AppError? =
        when {
            existing == null && upsert.kind == EntityKind.UNKNOWN ->
                ValidationError(message = "Choose what kind of entry this is.", field = "kind")
            existing == null -> null
            !canSee(caller, existing.homeSeriesId, existing.homeBookId) ->
                EntityError.NotFound(debugInfo = "entity=${upsert.id.value}")
            existing.homeSeriesId != upsert.homeSeriesId?.value || existing.homeBookId != upsert.homeBookId?.value ->
                ValidationError(message = "An entry stays in the series or book it was created in.")
            else -> null
        }

    private fun validate(upsert: EntityUpsert): ValidationError? =
        when {
            upsert.name.isBlank() -> ValidationError(message = "Give this entry a name.", field = "name")
            (upsert.descriptor?.trim()?.length ?: 0) > MAX_DESCRIPTOR ->
                ValidationError(message = "Keep the description to $MAX_DESCRIPTOR characters.", field = "descriptor")
            (upsert.homeSeriesId == null) == (upsert.homeBookId == null) ->
                ValidationError(message = "An entry belongs to exactly one series or book.")
            else -> null
        }

    private suspend fun visibleLive(
        caller: UserPrincipal,
        id: EntityId,
    ): EntitySyncPayload? =
        entityRepo
            .findById(id)
            ?.takeIf { it.deletedAt == null && canSee(caller, it.homeSeriesId, it.homeBookId) }

    private suspend fun canSee(
        caller: UserPrincipal,
        homeSeriesId: String?,
        homeBookId: String?,
    ): Boolean = accessPolicy.canSeeEntityHome(caller.userId.value, caller.role, homeSeriesId, homeBookId)

    private fun <T> denied(): AppResult<T> = AppResult.Failure(AuthError.PermissionDenied())

    private fun <T> notFound(id: EntityId): AppResult<T> = AppResult.Failure(EntityError.NotFound(debugInfo = "entity=${id.value}"))

    private companion object {
        const val MAX_DESCRIPTOR = 60
    }
}
