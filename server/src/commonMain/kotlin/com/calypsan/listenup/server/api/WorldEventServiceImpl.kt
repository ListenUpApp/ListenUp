package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.WorldEventService
import com.calypsan.listenup.api.dto.world.EventsBatch
import com.calypsan.listenup.api.dto.world.WorldEventOp
import com.calypsan.listenup.api.dto.world.WorldEventUpsert
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.ValidationError
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.WorldEventSyncPayload
import com.calypsan.listenup.api.sync.WorldEventType
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.auth.UserPermissionPolicy
import com.calypsan.listenup.server.sync.WorldEventRepository

/**
 * [WorldEventService] implementation.
 *
 * Events are library-shared curated world data, dual-homed under exactly one of a series or a
 * standalone book — there is no per-caller ownership the way [ReadingOrderServiceImpl] gates
 * reading orders. [applyBatch] is gated on the metadata-edit permission via [permissionPolicy],
 * the same [requireCanEdit] pattern [EntityServiceImpl]/[SeriesServiceImpl] use: ROOT/ADMIN pass
 * implicitly, a MEMBER passes iff their `canEdit` flag is set, and an absent principal — a wiring
 * bug, since route handlers always [copyWith] the authenticated caller — is denied with
 * [AuthError.PermissionDenied]. The gate is checked ONCE for the whole batch, then every
 * [WorldEventOp.Upsert] in it is shape-validated by [validateUpsert] BEFORE
 * [worldEventRepo.applyBatch][WorldEventRepository.applyBatch] is ever called — a shape failure
 * never touches the database, let alone partially applies. [listForBook]/[listForEntity]/
 * [listForWorld] are open to any authenticated caller: events aren't access-gated per-book the way
 * [BookService] content is.
 *
 * Route handlers call [copyWith] to bind each request to the authenticated caller; the Koin
 * singleton carries an unscoped placeholder that yields no principal.
 */
internal class WorldEventServiceImpl(
    private val worldEventRepo: WorldEventRepository,
    private val permissionPolicy: UserPermissionPolicy,
    private val principal: PrincipalProvider = PrincipalProvider.None,
) : WorldEventService {
    /** Returns a copy scoped to the given [principal]. Route handlers call this per-request. */
    fun copyWith(principal: PrincipalProvider): WorldEventServiceImpl =
        WorldEventServiceImpl(worldEventRepo, permissionPolicy, principal)

    /**
     * Content-metadata edits are gated on the per-user `canEdit` flag. ROOT/ADMIN pass
     * implicitly; a MEMBER passes iff their flag is set (fresh DB lookup per call). An absent
     * principal — a wiring bug, since route handlers always [copyWith] the authenticated caller —
     * is denied. Returns null when permitted; the denial otherwise.
     */
    private suspend fun requireCanEdit(): AppError? {
        val p = principal.current() ?: return AuthError.PermissionDenied()
        return permissionPolicy.requireCanEdit(p.userId, p.role)
    }

    override suspend fun applyBatch(batch: EventsBatch): AppResult<Unit> {
        requireCanEdit()?.let { return AppResult.Failure(it) }
        for (op in batch.ops) {
            if (op is WorldEventOp.Upsert) {
                validateUpsert(op.upsert)?.let { return AppResult.Failure(it) }
            }
        }
        return worldEventRepo.applyBatch(batch.ops)
    }

    override suspend fun listForBook(bookId: String): AppResult<List<WorldEventSyncPayload>> {
        principal.current() ?: return AppResult.Failure(AuthError.PermissionDenied())
        return AppResult.Success(worldEventRepo.listForBook(bookId))
    }

    override suspend fun listForEntity(entityId: String): AppResult<List<WorldEventSyncPayload>> {
        principal.current() ?: return AppResult.Failure(AuthError.PermissionDenied())
        return AppResult.Success(worldEventRepo.listForEntity(entityId))
    }

    override suspend fun listForWorld(
        homeSeriesId: String?,
        homeBookId: String?,
    ): AppResult<List<WorldEventSyncPayload>> {
        principal.current() ?: return AppResult.Failure(AuthError.PermissionDenied())
        validateHome(homeSeriesId, homeBookId)?.let { return AppResult.Failure(it) }
        return AppResult.Success(worldEventRepo.listForWorld(homeSeriesId, homeBookId))
    }

    /**
     * Shape-validates a single [WorldEventOp.Upsert]'s [WorldEventUpsert] before it ever reaches
     * [worldEventRepo]: the dual-home rule (mirroring [EntityServiceImpl.validateHome]), the
     * anchor pairing ([WorldEventUpsert.bookId] / [WorldEventUpsert.positionMs] must be set
     * together, per [WorldEventSyncPayload]'s KDoc), and the per-[WorldEventType] rules. Returns
     * null when [upsert] is valid, a [ValidationError] otherwise.
     */
    private fun validateUpsert(upsert: WorldEventUpsert): ValidationError? {
        validateHome(upsert.homeSeriesId, upsert.homeBookId)?.let { return it }
        validateAnchor(upsert)?.let { return it }
        return validateType(upsert)
    }

    /**
     * Events are dual-homed: exactly one of [homeSeriesId] / [homeBookId] must be non-null.
     * Returns null when that rule holds, a [ValidationError] otherwise. Mirrors
     * [EntityServiceImpl.validateHome].
     */
    private fun validateHome(
        homeSeriesId: String?,
        homeBookId: String?,
    ): ValidationError? {
        val seriesHomeMissing = homeSeriesId == null
        val bookHomeMissing = homeBookId == null
        return if (seriesHomeMissing == bookHomeMissing) {
            ValidationError(message = "Exactly one of homeSeriesId or homeBookId must be set.")
        } else {
            null
        }
    }

    /**
     * [WorldEventUpsert.bookId] and [WorldEventUpsert.positionMs] are an anchor pair: either both
     * are set (the event is pinned to a book position) or neither is (the event carries no book
     * anchor). Returns null when that rule holds, a [ValidationError] otherwise.
     */
    private fun validateAnchor(upsert: WorldEventUpsert): ValidationError? {
        val bookSet = upsert.bookId != null
        val positionSet = upsert.positionMs != null
        return if (bookSet != positionSet) {
            ValidationError(message = "bookId and positionMs must be set together.")
        } else {
            null
        }
    }

    /**
     * Per-[WorldEventType] shape rules: [WorldEventType.NOTE] requires non-blank
     * [WorldEventUpsert.text] (it is the only content the event carries);
     * [WorldEventType.MOVES_TO] requires a non-null [WorldEventUpsert.objectEntityId] (the
     * destination). Every other type — including [WorldEventType.DEPARTS] (object optional) and
     * the reserved types — carries no further requirement beyond the common shape [validateHome]
     * and [validateAnchor] already checked. Returns null when [upsert] satisfies its type's rule,
     * a [ValidationError] otherwise.
     */
    private fun validateType(upsert: WorldEventUpsert): ValidationError? =
        when (upsert.type) {
            WorldEventType.NOTE -> {
                if (upsert.text.isBlank()) {
                    ValidationError(message = "text must not be blank for a NOTE event.", field = "text")
                } else {
                    null
                }
            }

            WorldEventType.MOVES_TO -> {
                if (upsert.objectEntityId == null) {
                    ValidationError(
                        message = "objectEntityId is required for a MOVES_TO event.",
                        field = "objectEntityId",
                    )
                } else {
                    null
                }
            }

            WorldEventType.ENTERS_SCENE,
            WorldEventType.EXITS_SCENE,
            WorldEventType.DEPARTS,
            WorldEventType.ALIAS,
            WorldEventType.BORN,
            WorldEventType.DIES,
            WorldEventType.ITEM_TRANSFER,
            WorldEventType.RELATIONSHIP_CHANGE,
            -> {
                null
            }
        }
}
