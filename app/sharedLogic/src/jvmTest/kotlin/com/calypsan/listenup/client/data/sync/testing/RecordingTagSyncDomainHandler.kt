package com.calypsan.listenup.client.data.sync.testing

import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Test-only `SyncDomainHandler<Tag>` that records every event/item it observes.
 * Tier 3 e2e tests assert against [observed] / [catchUpObserved].
 *
 * Property/init order: `domainName` and `payloadSerializer` MUST be declared
 * before the `init` block — `ClientSyncDomainRegistry.register` reads
 * `handler.domainName` synchronously.
 */
internal class RecordingTagSyncDomainHandler(
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<Tag> {
    override val domainName = "tags"
    override val payloadSerializer = Tag.serializer()

    override fun syncId(item: Tag): String = item.id

    init {
        registry.register(this)
    }

    private val replayCapacity = 64
    private val bufferCapacity = 64

    private val observedFlow =
        MutableSharedFlow<SyncEvent<Tag>>(
            replay = replayCapacity,
            extraBufferCapacity = bufferCapacity,
        )
    val observed: SharedFlow<SyncEvent<Tag>> = observedFlow.asSharedFlow()

    private val catchUpObservedFlow =
        MutableSharedFlow<Pair<Tag, Boolean>>(
            replay = replayCapacity,
            extraBufferCapacity = bufferCapacity,
        )
    val catchUpObserved: SharedFlow<Pair<Tag, Boolean>> = catchUpObservedFlow.asSharedFlow()

    override suspend fun onEvent(event: SyncEvent<Tag>): AppResult<Unit> {
        observedFlow.emit(event)
        return AppResult.Success(Unit)
    }

    override suspend fun onCatchUpItem(
        item: Tag,
        isTombstone: Boolean,
    ): AppResult<Unit> {
        catchUpObservedFlow.emit(item to isTombstone)
        return AppResult.Success(Unit)
    }

    override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = emptyList()
}
