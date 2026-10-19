package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.data.local.db.SyncCursorDao
import com.calypsan.listenup.client.data.local.db.SyncCursorEntity

/**
 * Persistence wrapper around per-domain sync cursors.
 *
 * One row per registered domain; the value is the highest revision the client
 * has fully applied. Used at engine start to bootstrap each domain's catch-up
 * `?since=<rev>`, and on firehose reconnect to seed `lastEventId` from
 * [highestCursor].
 */
internal class SyncCursorStore(
    private val dao: SyncCursorDao,
) {
    suspend fun getCursor(domainName: String): Long? = dao.getCursor(domainName)

    /**
     * The catch-up path: create the cursor for [domainName], or advance it to [revision]
     * monotonically, so a lower value is ignored and the cursor never regresses. Only a pull
     * may create a cursor; live frames use [advanceFromLiveFrame].
     */
    suspend fun setCursor(
        domainName: String,
        revision: Long,
    ) {
        dao.setCursorMonotonic(domainName = domainName, revision = revision)
    }

    /**
     * Advance [domainName]'s cursor from a live firehose frame: monotonic, and only when a pull has already created
     * the cursor. Only catch-up ([setCursor], [resetCursor]) creates one; see [SyncCursorDao.advanceExistingCursor].
     */
    suspend fun advanceFromLiveFrame(
        domainName: String,
        revision: Long,
    ) {
        dao.advanceExistingCursor(domainName = domainName, revision = revision)
    }

    /**
     * Force the cursor for [domainName] to exactly [revision], bypassing the monotonic guard —
     * used by the from-zero re-baseline (`catchUpFromZero`), which deliberately re-establishes the
     * cursor from server truth and must be able to LOWER a stale-high local value. This is the only
     * sanctioned regression path; incremental catch-up goes through [setCursor], live frames through [advanceFromLiveFrame].
     */
    suspend fun resetCursor(
        domainName: String,
        revision: Long,
    ) {
        dao.setCursor(SyncCursorEntity(domainName = domainName, revision = revision))
    }

    /**
     * The highest revision this device has seen in any domain, or null when it has seen none. A 0 cursor records a
     * pull that found its domain empty, not a revision seen, so a device whose every pull was empty reads null: the
     * firehose then subscribes live, as before empty pulls recorded a cursor, rather than resuming from 0 and forcing
     * a stale-cursor recovery, and the reconciler has nothing to compare yet.
     */
    suspend fun highestCursor(): Long? = dao.all().maxOfOrNull { it.revision }?.takeIf { it > 0 }
}

/**
 * The firehose dispatcher's cursor advance, as production wires it: [SyncCursorStore.advanceFromLiveFrame], so a live
 * frame never creates a domain's cursor. One definition, so the module and its tests cannot wire it differently.
 */
internal fun liveFrameCursorAdvance(store: SyncCursorStore): suspend (domainName: String, revision: Long) -> Unit =
    { domainName, revision -> store.advanceFromLiveFrame(domainName, revision) }
