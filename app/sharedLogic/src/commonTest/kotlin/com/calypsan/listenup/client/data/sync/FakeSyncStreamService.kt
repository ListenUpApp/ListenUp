package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.streaming.RpcEvent
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.SyncFrame
import com.calypsan.listenup.api.sync.SyncPage
import com.calypsan.listenup.api.sync.TargetedMatch
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.KSerializer

/**
 * A [SyncStreamService] whose every member throws until a test overrides it.
 *
 * Most tests exercise one half of the service — the firehose or the pull — and stubbing the other
 * four members with plausible empty values would let a test pass by reaching a method it never
 * meant to call. Throwing instead means an unexpected call names itself.
 */
internal abstract class FakeSyncStreamService : SyncStreamService {
    override fun observeEvents(sinceRevision: Long?): Flow<RpcEvent<SyncFrame>> = error("observeEvents was not stubbed")

    override suspend fun pullDomain(
        domain: String,
        since: Long,
        limit: Int,
    ): AppResult<SyncPage> = error("pullDomain was not stubbed (domain=$domain, since=$since)")

    override suspend fun pullByIds(
        domain: String,
        match: TargetedMatch,
        ids: List<String>,
    ): AppResult<SyncPage> = error("pullByIds was not stubbed (domain=$domain, match=$match)")

    override suspend fun digest(
        domain: String,
        cursor: Long,
    ): AppResult<DomainDigest> = error("digest was not stubbed (domain=$domain)")

    override suspend fun listDomains(): AppResult<List<String>> = error("listDomains was not stubbed")
}

/**
 * Builds the wire shape the server would produce for [items]: a typed envelope whose rows are
 * each encoded with [serializer], exactly as `SyncStreamServiceImpl.toSyncPage` does.
 *
 * Tests script pages through this rather than hand-writing JSON, so a payload field rename breaks
 * them at compile time instead of silently producing a page that no longer decodes.
 */
internal fun <T : Any> syncPageOf(
    domain: String,
    serializer: KSerializer<T>,
    items: List<T>,
    nextCursor: Long?,
    hasMore: Boolean,
): SyncPage =
    SyncPage(
        domain = domain,
        items = items.map { contractJson.encodeToString(serializer, it) },
        nextCursor = nextCursor,
        hasMore = hasMore,
    )
