package com.calypsan.listenup.client.data.sync.handlers

import com.calypsan.listenup.api.sync.LibraryFolderSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.db.entity.LibraryFolderEntity
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client-side sync handler for the `library_folders` domain.
 *
 * Applies server sync events into the Room `library_folders` table. Folder
 * rows are created and deleted exclusively by the `LibraryAdminService` RPC;
 * there is no client-side write path in this phase.
 *
 * Folder rows carry a foreign key to `libraries`. The server guarantees that
 * the parent library sync event precedes folder events during catch-up, so
 * the FK constraint is satisfied.
 *
 * Self-registers in [ClientSyncDomainRegistry] at construction.
 */
class LibraryFolderSyncDomainHandler(
    private val database: ListenUpDatabase,
    private val transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<LibraryFolderSyncPayload> {
    override val domainName: String = "library_folders"
    override val payloadSerializer = LibraryFolderSyncPayload.serializer()

    init {
        registry.register(this)
    }

    override suspend fun onEvent(
        event: SyncEvent<LibraryFolderSyncPayload>,
        isOwnEcho: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, event.id, logger) {
            when (event) {
                is SyncEvent.Created -> {
                    upsert(event.payload)
                }

                is SyncEvent.Updated -> {
                    upsert(event.payload)
                }

                is SyncEvent.Deleted -> {
                    database.libraryFolderDao().softDelete(
                        id = event.id,
                        deletedAt = event.occurredAt,
                        revision = event.revision,
                    )
                }
            }
        }

    override suspend fun onCatchUpItem(
        item: LibraryFolderSyncPayload,
        isTombstone: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, item.id, logger) {
            if (isTombstone) {
                database.libraryFolderDao().softDelete(
                    id = item.id,
                    deletedAt = item.deletedAt ?: item.updatedAt,
                    revision = item.revision,
                )
            } else {
                upsert(item)
            }
        }

    private suspend fun upsert(payload: LibraryFolderSyncPayload) {
        database.libraryFolderDao().upsert(
            LibraryFolderEntity(
                id = payload.id,
                libraryId = payload.libraryId,
                rootPath = payload.rootPath,
                createdAt = payload.createdAt,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
                clientOpId = null,
            ),
        )
    }
}
