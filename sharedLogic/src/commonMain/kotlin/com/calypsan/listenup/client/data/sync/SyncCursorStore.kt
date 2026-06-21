package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.data.local.db.SyncCursorDao
import com.calypsan.listenup.client.data.local.db.SyncCursorEntity

/**
 * Persistence wrapper around per-domain sync cursors.
 *
 * One row per registered domain; the value is the highest revision the client
 * has fully applied. Used at engine start to bootstrap each domain's catch-up
 * `?since=<rev>`, and on SSE reconnect to set `Last-Event-Id` to
 * [highestCursor].
 */
internal class SyncCursorStore(
    private val dao: SyncCursorDao,
) {
    suspend fun getCursor(domainName: String): Long? = dao.getCursor(domainName)

    suspend fun setCursor(
        domainName: String,
        revision: Long,
    ) {
        dao.setCursor(SyncCursorEntity(domainName = domainName, revision = revision))
    }

    /** Max cursor across all domains, or null when no domain has a stored cursor. */
    suspend fun highestCursor(): Long? = dao.all().maxOfOrNull { it.revision }
}
