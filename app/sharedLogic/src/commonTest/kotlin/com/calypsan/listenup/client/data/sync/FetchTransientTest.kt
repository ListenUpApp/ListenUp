package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.SyncPage
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.api.sync.TargetedMatch
import com.calypsan.listenup.client.data.local.db.SyncCursorDao
import com.calypsan.listenup.client.data.local.db.SyncCursorEntity
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.test.db.passThroughTransactionRunner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Pins [SyncCatchUpClient.fetchTransient] — the read half of the scoped `AccessChanged` delta:
 * it calls [com.calypsan.listenup.api.SyncStreamService.pullByIds] with the targeted
 * [TargetedMatch], applies each returned row, returns the non-tombstone ids that came back,
 * chunks over the per-request cap, and never advances the cursor.
 */
class FetchTransientTest :
    FunSpec({

        data class RecordedCall(
            val domain: String,
            val match: TargetedMatch,
            val ids: List<String>,
        )

        fun fakeService(record: MutableList<RecordedCall>): FakeSyncStreamService =
            object : FakeSyncStreamService() {
                override suspend fun pullByIds(
                    domain: String,
                    match: TargetedMatch,
                    ids: List<String>,
                ): AppResult<SyncPage> {
                    record += RecordedCall(domain, match, ids)
                    // Echo back a page whose items are exactly the requested ids (all live), so the
                    // test can assert the call carried them and the returned set is what came back.
                    val page =
                        syncPageOf(
                            domain = domain,
                            serializer = Tag.serializer(),
                            items = ids.map { Tag(it, it, it, 1L, 10L) },
                            nextCursor = null,
                            hasMore = false,
                        )
                    return AppResult.Success(page)
                }
            }

        fun tagHandler(seen: MutableList<Tag>): SyncDomainHandler<Tag> =
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
                    seen += item
                    return AppResult.Success(Unit)
                }

                override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = emptyList()
            }

        fun client(
            service: FakeSyncStreamService,
            dao: SyncCursorDao,
        ): SyncCatchUpClient =
            SyncCatchUpClient(
                channel = RpcChannel.forTest(service),
                store = SyncCursorStore(dao),
                transactionRunner = passThroughTransactionRunner(),
            )

        test("ByIds calls pullByIds with TargetedMatch.ID, applies returned rows, and returns their ids") {
            runTest {
                val calls = mutableListOf<RecordedCall>()
                val seen = mutableListOf<Tag>()
                val dao = InMemoryCursorDao()
                dao.setCursor(SyncCursorEntity("tags", 99L))
                val catchUp = client(fakeService(calls), dao)

                val result = catchUp.fetchTransient(tagHandler(seen), TargetedFetch.ByIds(listOf("a", "b")))

                result.shouldBeInstanceOf<AppResult.Success<Set<String>>>()
                result.data shouldContainExactlyInAnyOrder setOf("a", "b")
                seen.map { it.id } shouldContainExactlyInAnyOrder listOf("a", "b")
                calls.single().domain shouldBe "tags"
                calls.single().match shouldBe TargetedMatch.ID
                calls.single().ids shouldBe listOf("a", "b")
                // The persisted cursor is never touched by a transient fetch.
                dao.getCursor("tags") shouldBe 99L
            }
        }

        test("ByCollectionIds calls pullByIds with TargetedMatch.COLLECTION_ID") {
            runTest {
                val calls = mutableListOf<RecordedCall>()
                val catchUp = client(fakeService(calls), InMemoryCursorDao())

                catchUp.fetchTransient(tagHandler(mutableListOf()), TargetedFetch.ByCollectionIds(listOf("c1")))

                calls.single().domain shouldBe "tags"
                calls.single().match shouldBe TargetedMatch.COLLECTION_ID
                calls.single().ids shouldBe listOf("c1")
            }
        }

        test("a scope over the per-request cap is chunked, never truncated") {
            runTest {
                val calls = mutableListOf<RecordedCall>()
                val seen = mutableListOf<Tag>()
                val catchUp = client(fakeService(calls), InMemoryCursorDao())
                val ids = (1..250).map { "id$it" }

                val result = catchUp.fetchTransient(tagHandler(seen), TargetedFetch.ByIds(ids))

                result.shouldBeInstanceOf<AppResult.Success<Set<String>>>()
                // 250 ids / 100-per-request cap = 3 requests; every id comes back (no truncation).
                calls.size shouldBe 3
                result.data shouldContainExactly ids.toSet()
            }
        }
    })

private class InMemoryCursorDao : SyncCursorDao {
    private val cursors = mutableMapOf<String, Long>()

    override suspend fun getCursor(domainName: String): Long? = cursors[domainName]

    override suspend fun setCursor(entity: SyncCursorEntity) {
        cursors[entity.domainName] = entity.revision
    }

    override suspend fun setCursorMonotonic(
        domainName: String,
        revision: Long,
    ) {
        val current = cursors[domainName]
        if (current == null || revision > current) cursors[domainName] = revision
    }

    override suspend fun all(): List<SyncCursorEntity> = cursors.map { (domain, rev) -> SyncCursorEntity(domainName = domain, revision = rev) }

    override suspend fun deleteAll() {
        cursors.clear()
    }
}
