package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase

/** Which kind of row an `external_refs` entry belongs to — its `entity_kind` column value. */
internal enum class ExternalRefKind(
    val column: String,
) {
    BOOK("book"),
    CONTRIBUTOR("contributor"),
}

/**
 * Replaces [entityId]'s refs with [refs] (already reconciled — one per provider). Must run inside the
 * owning entity's open transaction, so a ref change rides that entity's revision bump and sync frame.
 */
internal fun ListenUpDatabase.replaceExternalRefs(
    kind: ExternalRefKind,
    entityId: String,
    refs: List<ExternalRef>,
) {
    externalRefsQueries.deleteForEntity(entity_kind = kind.column, entity_id = entityId)
    refs.forEach { ref ->
        externalRefsQueries.insert(
            entity_kind = kind.column,
            entity_id = entityId,
            provider = ref.provider,
            external_id = ref.id,
            region = ref.region,
        )
    }
}

/** Every ref of [entityIds], grouped by entity id, each list ordered by provider. Inside an open transaction. */
internal fun ListenUpDatabase.readExternalRefs(
    kind: ExternalRefKind,
    entityIds: Collection<String>,
): Map<String, List<ExternalRef>> =
    externalRefsQueries
        .selectByEntityIds(entity_kind = kind.column, entity_ids = entityIds)
        .executeAsList()
        .groupBy({ it.entity_id }, { ExternalRef(provider = it.provider, id = it.external_id, region = it.region) })
