package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.EntityService
import com.calypsan.listenup.api.dto.entity.EntityUpsert
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.EntitySyncPayload
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.sync.EntityRepository
import kotlin.time.Clock

/**
 * [EntityService] implementation.
 *
 * Entities are library-shared curated world data, namespaced under a series — there
 * is no per-caller ownership the way [ReadingOrderServiceImpl] gates reading orders.
 * [upsertEntity] and [deleteEntity] are gated on the metadata-edit permission via
 * [permissionPolicy], the same [requireCanEdit] pattern
 * [SeriesServiceImpl]/[BookServiceImpl] use: ROOT/ADMIN pass implicitly, a MEMBER
 * passes iff their `canEdit` flag is set, and an absent principal — a wiring bug,
 * since route handlers always [copyWith] the authenticated caller — is denied with
 * [AuthError.PermissionDenied]. [listEntitiesForSeries] is open to any authenticated
 * caller: entities aren't access-gated per-book the way [BookService] content is.
 *
 * Route handlers call [copyWith] to bind each request to the authenticated caller;
 * the Koin singleton carries an unscoped placeholder that yields no principal.
 */
internal class EntityServiceImpl(
    private val entityRepo: EntityRepository,
    private val permissionPolicy: UserPermissionPolicy,
    private val principal: PrincipalProvider = PrincipalProvider.None,
    private val clock: Clock = Clock.System,
) : EntityService {
    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): EntityServiceImpl =
        EntityServiceImpl(entityRepo, permissionPolicy, principal, clock)

    /**
     * Content-metadata edits are gated on the per-user `canEdit` flag. ROOT/ADMIN pass
     * implicitly; a MEMBER passes iff their flag is set (fresh DB lookup per call). An
     * absent principal — a wiring bug, since route handlers always [copyWith] the
     * authenticated caller — is denied. Returns null when permitted; the denial otherwise.
     */
    private suspend fun requireCanEdit(): AppError? {
        val p = principal.current() ?: return AuthError.PermissionDenied()
        return permissionPolicy.requireCanEdit(p.userId, p.role)
    }

    override suspend fun upsertEntity(upsert: EntityUpsert): AppResult<EntitySyncPayload> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        val existing = entityRepo.findById(upsert.id)
        val now = clock.now().toEpochMilliseconds()
        val payload =
            EntitySyncPayload(
                id = upsert.id,
                kind = upsert.kind,
                name = upsert.name,
                homeSeriesId = upsert.homeSeriesId,
                imageRef = upsert.imageRef,
                bioEntries = upsert.bioEntries,
                revision = existing?.revision ?: 0L,
                updatedAt = now,
                createdAt = existing?.createdAt ?: now,
                deletedAt = null,
            )
        return entityRepo.upsertEntity(payload)
    }

    override suspend fun deleteEntity(id: String): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        return entityRepo.softDelete(id)
    }

    override suspend fun listEntitiesForSeries(seriesId: String): AppResult<List<EntitySyncPayload>> {
        principal.current() ?: return AppResult.Failure(AuthError.PermissionDenied())
        return AppResult.Success(entityRepo.listBySeries(seriesId))
    }
}
