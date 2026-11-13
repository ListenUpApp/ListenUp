package com.calypsan.listenup.client.data.local.db

import androidx.room3.Entity

/**
 * An entity owed a re-fetch of server truth because the local edit it shows was never accepted: its outbox
 * op dead-lettered or was dismissed. The local row keeps its `(id, revision)`, so no digest, catch-up or
 * echo would ever repair it — only this re-fetch does, and it is stored rather than held in memory so a
 * process killed before the fetch runs still owes it on the next start.
 *
 * One row per entity: a second request for the same entity replaces the first.
 *
 * @property domainName The outbox channel the refused op rode, as `PendingOperationV2Entity.domainName`.
 * @property entityId The op's outbox key, as `PendingOperationV2Entity.entityId`.
 * @property requestId Fresh per request. Settling a heal deletes the row only while it still holds the
 *   request that heal answered, so a request made while the fetch was in flight is not lost.
 */
@Entity(tableName = "pending_heal", primaryKeys = ["domainName", "entityId"])
internal data class PendingHealEntity(
    val domainName: String,
    val entityId: String,
    val requestId: String,
)
