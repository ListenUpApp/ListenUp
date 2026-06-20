@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.SharePermission
import com.calypsan.listenup.api.sync.CollectionShareSyncPayload
import com.calypsan.listenup.server.db.UserRoleColumn
import com.calypsan.listenup.server.services.LibraryRegistry
import com.calypsan.listenup.server.sync.CollectionGrantRepository
import com.calypsan.listenup.server.sync.CollectionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.uuid.Uuid

private val logger = KotlinLogging.logger {}

/**
 * Best-effort collaborator that grants the per-library ALL_BOOKS system collection to a new
 * MEMBER user immediately after their account row is committed.
 *
 * ROOT and ADMIN users bypass collection-visibility filtering at the service layer, so they
 * intentionally receive no grant — calling [grantDefaultAllBooks] for them is a no-op.
 *
 * Injected as a nullable parameter in [com.calypsan.listenup.server.auth.AuthServiceImpl] and
 * [InviteServiceImpl] so those modules assemble independently of the collections module
 * (phased startup, test containers). A null value silently skips grant issuance.
 */
class DefaultAllBooksGrantIssuer(
    private val collectionGrantRepository: CollectionGrantRepository,
    private val collectionRepository: CollectionRepository,
    private val libraryRegistry: LibraryRegistry,
    private val clock: Clock = Clock.System,
) {
    /**
     * Issues an ALL_BOOKS read grant to [userId] if [role] is [UserRoleColumn.MEMBER].
     *
     * **Idempotent / self-heal-safe:** before upserting, checks whether a live grant already
     * exists. If one is found, returns immediately (no-op) — safe to call on every login as a
     * self-heal pass. Only inserts when the grant is actually missing.
     *
     * Never throws: [CancellationException] is re-raised (structured-concurrency contract);
     * all other failures are logged at ERROR (because a missing grant leaves the member with an
     * entirely empty library) and swallowed so user creation/login is never blocked.
     */
    suspend fun grantDefaultAllBooks(
        userId: String,
        role: UserRoleColumn,
    ) {
        if (role != UserRoleColumn.MEMBER) return
        try {
            val libraryId = libraryRegistry.currentLibrary().value
            val allBooksId =
                collectionRepository.findSystemCollection(libraryId, SYSTEM_TYPE_ALL_BOOKS)?.id
            if (allBooksId == null) {
                logger.error {
                    "ALL_BOOKS missing for library $libraryId — MEMBER $userId has NO default grant " +
                        "and will see an EMPTY library until reconciled"
                }
                return
            }
            // Idempotency check: if a live grant already exists, skip the upsert.
            val existing = collectionGrantRepository.findActiveGrant(allBooksId, userId)
            if (existing != null) return
            val now = clock.now().toEpochMilliseconds()
            collectionGrantRepository.upsert(
                CollectionShareSyncPayload(
                    id = Uuid.random().toString(),
                    collectionId = allBooksId,
                    sharedWithUserId = userId,
                    sharedByUserId = SYSTEM_OWNER_ID,
                    permission = SharePermission.Read,
                    revision = 0L,
                    updatedAt = now,
                    deletedAt = null,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) {
                "default ALL_BOOKS grant failed for MEMBER $userId — they will see an EMPTY library until reconciled (next login retries)"
            }
        }
    }
}
