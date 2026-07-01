package com.calypsan.listenup.client.data.sync.handlers

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.AdminUserRosterSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.client.data.local.db.AdminUserRosterEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Client-side sync handler for the admin-only `admin_user_roster` domain.
 *
 * Each row is a server-maintained materialized view of a user's admin-visible identity
 * (email, role, status, share permission); the client always replaces its local row
 * unconditionally (server-wins, no client-writable fields). Delivery is gated to admins
 * on the firehose — non-admins never receive these events.
 *
 * Self-registers in [ClientSyncDomainRegistry] at construction.
 */
internal class AdminUserRosterSyncDomainHandler(
    private val database: ListenUpDatabase,
    private val transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<AdminUserRosterSyncPayload> {
    override val domainName: String = "admin_user_roster"
    override val payloadSerializer = AdminUserRosterSyncPayload.serializer()

    override fun syncId(item: AdminUserRosterSyncPayload): String = item.id

    init {
        registry.register(this)
    }

    override suspend fun onEvent(
        event: SyncEvent<AdminUserRosterSyncPayload>,
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
                    database.adminUserRosterDao().softDelete(
                        id = event.id,
                        deletedAt = event.occurredAt,
                        revision = event.revision,
                    )
                }
            }
        }

    override suspend fun onCatchUpItem(
        item: AdminUserRosterSyncPayload,
        isTombstone: Boolean,
    ): AppResult<Unit> =
        transactionRunner.applyEventAtomically(domainName, item.id, logger) {
            if (isTombstone) {
                database.adminUserRosterDao().softDelete(
                    id = item.id,
                    deletedAt = item.deletedAt ?: item.updatedAt,
                    revision = item.revision,
                )
            } else {
                upsert(item)
            }
        }

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> =
        database.adminUserRosterDao().digestRows(maxRevision).map { it.id to it.revision }

    /**
     * Unconditionally upsert [payload] into [AdminUserRosterEntity]. The server is the
     * sole writer — no merge logic, no local-only columns to preserve.
     */
    private suspend fun upsert(payload: AdminUserRosterSyncPayload) {
        database.adminUserRosterDao().upsert(
            AdminUserRosterEntity(
                id = payload.id,
                email = payload.email,
                displayName = payload.displayName,
                role = payload.role,
                status = payload.status,
                canShare = payload.canShare,
                accountCreatedAt = payload.accountCreatedAt,
                revision = payload.revision,
                deletedAt = payload.deletedAt,
            ),
        )
    }
}
