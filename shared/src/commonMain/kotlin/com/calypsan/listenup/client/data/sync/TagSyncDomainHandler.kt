package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.core.AppResult

/**
 * Validation-domain handler for Tags. Tags has no client UI nor Room entity in
 * this phase — this handler exists to exercise the engine's plumbing
 * (registration, dispatch, echo matching, catch-up) end-to-end against the
 * real server. Books-A's `BookSyncDomainHandler` is the same shape with real
 * Room persistence.
 */
class TagSyncDomainHandler(
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<Tag> {
    override val domainName: String = "tags"
    override val payloadSerializer = Tag.serializer()

    init {
        registry.register(this)
    }

    override suspend fun onEvent(
        event: SyncEvent<Tag>,
        isOwnEcho: Boolean,
    ): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun onCatchUpItem(
        item: Tag,
        isTombstone: Boolean,
    ): AppResult<Unit> = AppResult.Success(Unit)
}
