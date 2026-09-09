package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.SyncPage
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.local.db.SyncCursorDao
import com.calypsan.listenup.client.data.local.db.SyncCursorEntity
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.test.db.passThroughTransactionRunner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest

/**
 * Pins that ONE row a build cannot decode degrades its catch-up page instead of freezing the domain.
 *
 * Before this, the whole page decoded in a single unguarded `map { decodeFromString(...) }`: a row
 * carrying a wire enum member (or any shape) the client did not understand threw out of the decode,
 * `pullCatching` folded it to `AppResult.Failure` BEFORE any cursor advance, and the next pass
 * re-fetched the identical `?since=` page and failed identically — permanently, on that device.
 *
 * The fix treats an undecodable row exactly as a failed apply, which is what lets the existing
 * digest-backstop / opt-out bookkeeping make the right call with no new policy.
 */
class SyncPageDecodeResilienceTest :
    FunSpec({

        // The shape is right, one value is not: `revision` must be a Long and "not-a-number" cannot
        // be parsed as one even in lenient mode. An *unknown key* would NOT do — `ignoreUnknownKeys`
        // tolerates those — so a wrong-typed required field is the reliable way to force a throw.
        val undecodableRow = """{"id":"bad","name":"Bad","slug":"bad","revision":"not-a-number","updatedAt":150}"""

        fun pageWithHole(): SyncPage =
            SyncPage(
                domain = "tags",
                items =
                    listOf(
                        contractJson.encodeToString(Tag.serializer(), Tag("a", "alpha", "alpha", 1L, 100L)),
                        undecodableRow,
                        contractJson.encodeToString(Tag.serializer(), Tag("c", "gamma", "gamma", 3L, 300L)),
                    ),
                nextCursor = 3L,
                hasMore = false,
            )

        fun tagHandler(
            seenItems: MutableList<Tag>,
            hasBackstop: Boolean,
        ): SyncDomainHandler<Tag> =
            object : SyncDomainHandler<Tag> {
                override val domainName = "tags"
                override val payloadSerializer = Tag.serializer()
                override val hasDigestBackstop = hasBackstop

                override fun syncId(item: Tag): String = item.id

                override suspend fun onEvent(event: SyncEvent<Tag>): AppResult<Unit> = AppResult.Success(Unit)

                override suspend fun onCatchUpItem(
                    item: Tag,
                    isTombstone: Boolean,
                ): AppResult<Unit> {
                    seenItems += item
                    return AppResult.Success(Unit)
                }

                override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>>? = if (hasBackstop) emptyList() else null
            }

        fun clientFor(
            store: SyncCursorStore,
            requestedSince: MutableList<Long>,
        ): SyncCatchUpClient {
            val service =
                object : FakeSyncStreamService() {
                    override suspend fun pullDomain(
                        domain: String,
                        since: Long,
                        limit: Int,
                    ): AppResult<SyncPage> {
                        requestedSince += since
                        return AppResult.Success(pageWithHole())
                    }
                }
            return SyncCatchUpClient(
                channel = RpcChannel.forTest(service),
                store = store,
                transactionRunner = passThroughTransactionRunner(),
            )
        }

        test("digest-backed domain: an undecodable row is skipped, its neighbours apply, the cursor advances") {
            runTest {
                val seenItems = mutableListOf<Tag>()
                val store = SyncCursorStore(DecodeResilienceCursorDao())

                val result = clientFor(store, mutableListOf()).catchUp(tagHandler(seenItems, hasBackstop = true))

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                seenItems.map { it.id } shouldContainExactly listOf("a", "c")
                store.getCursor("tags") shouldBe 3L
            }
        }

        test("digest-opt-out domain: the cursor holds below the undecodable row so pullSince redelivers it") {
            runTest {
                val seenItems = mutableListOf<Tag>()
                val store = SyncCursorStore(DecodeResilienceCursorDao())
                val requestedSince = mutableListOf<Long>()

                val result = clientFor(store, requestedSince).catchUp(tagHandler(seenItems, hasBackstop = false))

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                // Rev 1 applied cleanly; rev 2 is the hole. Holding at 1 is what makes the next
                // pullSince(1) redeliver the row once the client understands it.
                store.getCursor("tags") shouldBe 1L
                requestedSince shouldContainExactly listOf(0L)
            }
        }
    })

/**
 * In-memory [SyncCursorDao] for tests — sidesteps Room so the suite runs in commonTest.
 * Mirrors the DAO contract: get returns null for unknown domains, set is upsert.
 */
private class DecodeResilienceCursorDao : SyncCursorDao {
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

    override suspend fun all(): List<SyncCursorEntity> =
        cursors.map { (domain, rev) ->
            SyncCursorEntity(
                domainName = domain,
                revision = rev,
            )
        }

    override suspend fun deleteAll() {
        cursors.clear()
    }
}
