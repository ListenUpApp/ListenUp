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

    /**
     * Advance the cursor for [domainName] to [revision], monotonically: a lower value is
     * ignored so the cursor never regresses. Buffered pre-disconnect frames can be applied
     * after a catch-up already advanced the cursor further; without the `MAX` guard that
     * later, lower write would rewind the cursor and force a redundant re-catch-up.
     */
    suspend fun setCursor(
        domainName: String,
        revision: Long,
    ) {
        dao.setCursorMonotonic(domainName = domainName, revision = revision)
    }

    /**
     * Force the cursor for [domainName] to exactly [revision], bypassing the monotonic guard —
     * used by the from-zero re-baseline (`catchUpFromZero`), which deliberately re-establishes the
     * cursor from server truth and must be able to LOWER a stale-high local value. This is the only
     * sanctioned regression path; the incremental/SSE path always goes through [setCursor].
     */
    suspend fun resetCursor(
        domainName: String,
        revision: Long,
    ) {
        dao.setCursor(SyncCursorEntity(domainName = domainName, revision = revision))
    }

    /** Max cursor across all domains, or null when no domain has a stored cursor. */
    suspend fun highestCursor(): Long? = dao.all().maxOfOrNull { it.revision }
}
