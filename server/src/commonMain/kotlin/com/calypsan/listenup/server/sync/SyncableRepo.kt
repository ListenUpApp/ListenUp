package com.calypsan.listenup.server.sync

import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.SyncFrame

/**
 * The minimal sync-substrate contract the firehose/catch-up plumbing
 * ([ChangeBus], [SyncRegistry], [SyncStreamServiceImpl]) needs from a
 * syncable-domain repository — independent of the storage engine behind it.
 *
 * Both bases implement it: the Exposed [SyncableRepository] (production today)
 * and the SQLDelight [SqlSyncableRepository] (the cutover twin). The consumers
 * speak this interface, so a repository on either engine registers, publishes,
 * and serves catch-up identically. Widening the consumers from the concrete
 * Exposed type to this interface is what lets both bases coexist on the branch
 * during the incremental Exposed → SQLDelight cutover.
 *
 * Kept deliberately small — only what the three consumers actually touch:
 *  - [domainName] — the registry key, the firehose frame's `domain`, the error domain.
 *  - [encodeItemAsJson] / [encodeSyncEventAsJson] — the type-erasure-defeating
 *    serializer helpers the pull and the firehose call on the `SyncableRepo<Any>`
 *    cast (the registry is keyed by `String`, so the element type is erased at lookup).
 *  - [pullSince] / [digest] — the catch-up + drift-detection reads.
 *
 * The single type parameter [T] (the aggregate's wire DTO) is what binds a
 * [BusEvent]'s `repo` to its `event` payload type at compile time — a
 * `SyncableRepo<Tag>` cannot carry a `Book` event. `ID` is intentionally absent:
 * no consumer touches it.
 */
interface SyncableRepo<T : Any> {
    /** Stable domain key — registry id, firehose frame `domain`, error `domain`. */
    val domainName: String

    /**
     * Encodes ONE row to JSON via the repo's own concrete element serializer.
     *
     * The RPC catch-up pull carries a page as a list of encoded rows rather than one encoded
     * page, so the client never holds a whole-page JSON tree alongside the decoded rows and a
     * single malformed row fails alone.
     */
    fun encodeItemAsJson(item: T): String

    /**
     * Encodes a [SyncEvent] to a JSON string via the repo's own concrete element
     * serializer. The sync firehose calls this on the type-erased
     * `BusEvent<*>.repo` to serialise the event payload without losing type
     * information through the registry.
     */
    fun encodeSyncEventAsJson(event: SyncEvent<*>): String

    /**
     * Projects [event] to the wire [SyncFrame] the firehose delivers — `domain` + `revision` +
     * the event serialized via [encodeSyncEventAsJson]. This is the single conversion both the
     * firehose ([com.calypsan.listenup.server.sync.SyncStreamServiceImpl]) and the echo-in-response
     * mutation path use, so an originating device applies byte-identical frames to the ones every
     * other client receives — there is no second, drift-prone conversion.
     */
    fun toSyncFrame(event: SyncEvent<*>): SyncFrame =
        SyncFrame(
            domain = domainName,
            revision = event.revision,
            json = encodeSyncEventAsJson(event),
        )

    /**
     * Returns up to [limit] aggregates whose root row has `revision > cursor`,
     * ordered by revision ascending. See each base's override for the full
     * tombstone / `hasMore` / `nextCursor` contract.
     */
    suspend fun pullSince(
        userId: String?,
        cursor: Long,
        limit: Int,
        extraWhere: SqlFragment? = null,
    ): Page<T>

    /**
     * Access-filtered targeted read: the aggregates whose [matchColumn] is in [matchValues] and
     * the caller can still see — the read half of the scoped `AccessChanged` delta. The result is
     * ⊆ what an unbounded `since = 0` catch-up would return; an asked-about id that does not come
     * back is gone or no longer accessible and the client tombstones it. See the base override for
     * the full tombstone / access / trust contract. [matchColumn] is code-controlled, never user
     * input.
     */
    suspend fun pullByIds(
        userId: String?,
        matchColumn: String,
        matchValues: List<String>,
        extraWhere: SqlFragment? = null,
    ): Page<T>

    /**
     * Returns a [DomainDigest] over all rows with `revision <= cursor`,
     * soft-deleted rows included — the cheap drift-detection probe.
     */
    suspend fun digest(
        userId: String?,
        cursor: Long,
        extraWhere: SqlFragment? = null,
    ): DomainDigest
}
