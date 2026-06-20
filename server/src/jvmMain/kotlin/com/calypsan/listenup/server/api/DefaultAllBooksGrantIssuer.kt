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
     * Never throws: [CancellationException] is re-raised (structured-concurrency contract);
     * all other failures are logged at WARN and swallowed so user creation is never blocked.
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
                logger.warn {
                    "ALL_BOOKS system collection not found for library $libraryId — skipping default grant for user $userId"
                }
                return
            }
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
            logger.warn(e) { "default ALL_BOOKS grant failed for user $userId — user creation still succeeds" }
        }
    }
}
