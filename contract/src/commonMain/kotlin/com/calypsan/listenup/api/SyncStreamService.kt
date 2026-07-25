package com.calypsan.listenup.api

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.SyncControl
import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.api.sync.SyncPage
import com.calypsan.listenup.api.sync.TargetedMatch
import kotlinx.coroutines.flow.Flow
import kotlinx.rpc.annotations.Rpc

/**
 * The cross-domain sync surface: the push firehose plus the resumable pull that backs it.
 *
 * Both ride the same WebSocket as every unary RPC — one socket, one connection-truth. The
 * stream is only a delivery optimisation over the same revision cursor the pull uses, never
 * the correctness path: a client that misses frames, or whose cursor falls behind the
 * retention floor, converges by paging [pullDomain] from its stored cursor.
 */
@Rpc
interface SyncStreamService {
    /**
     * Subscribe to the firehose, resuming after [sinceRevision] (null = from the retention
     * floor). The server emits an immediate control hello (a [SyncFrame] carrying
     * [SyncControl.Heartbeat]) so subscribers can latch "connected" on stream-open, then a
     * heartbeat every 25s; a behind-floor cursor gets [SyncControl.CursorStale] and completion.
     */
    fun observeEvents(sinceRevision: Long?): Flow<RpcEvent<SyncFrame>>

    /**
     * One page of rows for [domain] with `revision > since`, current state, tombstones included
     * so clients can apply deletions. Clients page until [SyncPage.hasMore] is false, advancing
     * their stored cursor after each page so a crash mid-pagination resumes where it stopped.
     *
     * Access filtering applies per-domain at read time — a member never receives rows for
     * content outside their grants.
     *
     * Fails with [com.calypsan.listenup.api.error.SyncError.UnknownDomain] when this server does
     * not serve [domain].
     *
     * @param since the client's stored cursor; 0 pulls from the beginning.
     * @param limit rows per page, clamped server-side.
     */
    suspend fun pullDomain(
        domain: String,
        since: Long,
        limit: Int,
    ): AppResult<SyncPage>

    /**
     * Targeted, un-paged fetch of specific rows in [domain] — the scoped-delta counterpart to
     * [pullDomain], used when an access change names exactly which rows became reachable.
     *
     * [match] selects which column [ids] are matched against; the server maps it to a column and
     * enforces a per-domain allowlist, so a storage identifier never crosses the wire.
     *
     * Callers must **chunk** rather than truncate when they exceed the server's cap: a truncated
     * response is indistinguishable from "these ids are gone" and would wrongly tombstone rows
     * the caller can still reach. Over-cap requests fail with
     * [com.calypsan.listenup.api.error.SyncError.TooManyIds] carrying the limit to chunk to;
     * an unsupported column for the domain fails with
     * [com.calypsan.listenup.api.error.SyncError.UnsupportedMatch].
     */
    suspend fun pullByIds(
        domain: String,
        match: TargetedMatch,
        ids: List<String>,
    ): AppResult<SyncPage>

    /**
     * Count-and-hash of [domain] rows at or below [cursor] — the cheap convergence check that
     * tells a client whether its local state matches the server's without pulling rows.
     */
    suspend fun digest(
        domain: String,
        cursor: Long,
    ): AppResult<DomainDigest>

    /**
     * The domains this server serves, from the live repository registry.
     *
     * Discovery is deliberately dynamic: domains self-register at bootstrap rather than being
     * enumerated in the contract, so a server can add one without a contract change and an older
     * client simply never pulls it.
     */
    suspend fun listDomains(): AppResult<List<String>>
}
