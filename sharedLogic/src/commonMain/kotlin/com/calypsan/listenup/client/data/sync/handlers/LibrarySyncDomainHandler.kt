package com.calypsan.listenup.client.data.sync.handlers

import com.calypsan.listenup.api.sync.LibrarySyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.local.db.entity.LibraryEntity
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client-side sync handler for the `libraries` domain.
 *
 * Applies server sync events into the Room `libraries` table. Libraries are
 * cross-user and have no client-side write path in this phase — every
 * mutation goes through the `LibraryAdminService` RPC on the server and
 * arrives here via SSE or catch-up.
 *
 * No enrichment-preservation logic is needed: [LibrarySyncPayload] carries
 * every library column and none are written by any other domain.
 *
 * Self-registers in [ClientSyncDomainRegistry] at construction.
 */
class LibrarySyncDomainHandler(
    private val database: ListenUpDatabase,
    private val transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<LibrarySyncPayload> {
    override val domainName: String = "libraries"
    override val payloadSerializer = LibrarySyncPayload.serializer()

    init {
        registry.register(this)
    }

    override suspend fun onEvent(
        event: SyncEvent<LibrarySyncPayload>,
        isOwnEcho: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, event.id, logger) {
            when (event) {
                is SyncEvent.Created -> upsert(event.payload)
                is SyncEvent.Updated -> upsert(event.payload)
                is SyncEvent.Deleted -> {
                    database.libraryDao().softDelete(
                        id = event.id,
                        deletedAt = event.occurredAt,
                        revision = event.revision,
                    )
                }
            }
        }

    override suspend fun onCatchUpItem(
        item: LibrarySyncPayload,
        isTombstone: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, item.id, logger) {
            if (isTombstone) {
                database.libraryDao().softDelete(
                    id = item.id,
                    deletedAt = item.deletedAt ?: item.updatedAt,
                    revision = item.revision,
                )
            } else {
                upsert(item)
            }
        }

    private suspend fun upsert(payload: LibrarySyncPayload) {
        database.libraryDao().upsert(
            LibraryEntity(
                id = payload.id,
                name = payload.name,
                metadataPrecedence = payload.metadataPrecedence,
                accessMode = payload.accessMode,
                createdByUserId = payload.createdByUserId,
                createdAt = payload.createdAt,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
                clientOpId = null,
            ),
        )
    }
}
