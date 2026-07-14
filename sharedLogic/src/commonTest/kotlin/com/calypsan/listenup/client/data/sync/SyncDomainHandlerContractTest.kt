package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

class SyncDomainHandlerContractTest :
    FunSpec({

        test("a SyncDomainHandler returns AppResult.Success on a valid event") {
            runTest {
                val handler: SyncDomainHandler<Tag> =
                    object : SyncDomainHandler<Tag> {
                        override val domainName = "tags"
                        override val payloadSerializer = Tag.serializer()

                        override fun syncId(item: Tag): String = item.id

                        override suspend fun onEvent(
                            event: SyncEvent<Tag>,
                        ): AppResult<Unit> = AppResult.Success(Unit)

                        override suspend fun onCatchUpItem(
                            item: Tag,
                            isTombstone: Boolean,
                        ): AppResult<Unit> = AppResult.Success(Unit)

                        override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = emptyList()
                    }

                val event =
                    SyncEvent.Created(
                        id = "t1",
                        revision = 1L,
                        occurredAt = 100L,
                        clientOpId = null,
                        payload = Tag(id = "t1", name = "alpha", slug = "alpha", revision = 1L, updatedAt = 100L),
                    )
                val result = handler.onEvent(event)
                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
            }
        }

        test("onCatchUpItem distinguishes tombstones from live items via the boolean") {
            runTest {
                var tombstoneSeen = false
                val handler: SyncDomainHandler<Tag> =
                    object : SyncDomainHandler<Tag> {
                        override val domainName = "tags"
                        override val payloadSerializer = Tag.serializer()

                        override fun syncId(item: Tag): String = item.id

                        override suspend fun onEvent(
                            event: SyncEvent<Tag>,
                        ): AppResult<Unit> = AppResult.Success(Unit)

                        override suspend fun onCatchUpItem(
                            item: Tag,
                            isTombstone: Boolean,
                        ): AppResult<Unit> {
                            if (isTombstone) tombstoneSeen = true
                            return AppResult.Success(Unit)
                        }

                        override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = emptyList()
                    }

                val tombstone = Tag(id = "t1", name = "alpha", slug = "alpha", revision = 5L, updatedAt = 100L, deletedAt = 100L)
                handler.onCatchUpItem(tombstone, isTombstone = true)
                tombstoneSeen shouldBe true
            }
        }
    })
