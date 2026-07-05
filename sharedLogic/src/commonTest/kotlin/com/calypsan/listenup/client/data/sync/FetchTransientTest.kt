package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.local.db.SyncCursorDao
import com.calypsan.listenup.client.data.local.db.SyncCursorEntity
import com.calypsan.listenup.client.test.db.passThroughTransactionRunner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest

/**
 * Pins [SyncCatchUpClient.fetchTransient] — the read half of the scoped `AccessChanged` delta:
 * it hits the targeted `?ids=` / `?collectionIds=` endpoint, applies each returned row, returns the
 * non-tombstone ids that came back, chunks over the per-request cap, and never advances the cursor.
 */
class FetchTransientTest :
    FunSpec({

        fun mockClient(record: MutableList<Url>): HttpClient =
            HttpClient(
                MockEngine { req ->
                    record += req.url
                    // Echo back a Page whose items are exactly the requested ids (all live), so the
                    // test can assert the URL carried them and the returned set is what came back.
                    val requested =
                        (req.url.parameters["ids"] ?: req.url.parameters["collectionIds"] ?: "")
                            .split(",")
                            .filter { it.isNotBlank() }
                    val page =
                        Page(
                            items = requested.map { Tag(it, it, it, 1L, 10L) },
                            nextCursor = null,
                            hasMore = false,
                        )
                    respond(
                        content = contractJson.encodeToString(Page.serializer(Tag.serializer()), page),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                },
            ) {
                install(ContentNegotiation) { json(contractJson) }
            }

        fun tagHandler(seen: MutableList<Tag>): SyncDomainHandler<Tag> =
            object : SyncDomainHandler<Tag> {
                override val domainName = "tags"
                override val payloadSerializer = Tag.serializer()

                override fun syncId(item: Tag): String = item.id

                override suspend fun onEvent(
                    event: SyncEvent<Tag>,
                    isOwnEcho: Boolean,
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
            http: HttpClient,
            dao: SyncCursorDao,
        ): SyncCatchUpClient =
            SyncCatchUpClient(
                httpClientProvider = { http },
                serverUrlProvider = { "http://test" },
                store = SyncCursorStore(dao),
                transactionRunner = passThroughTransactionRunner(),
            )

        test("ByIds hits ?ids=, applies returned rows, and returns their ids") {
            runTest {
                val urls = mutableListOf<Url>()
                val seen = mutableListOf<Tag>()
                val dao = InMemoryCursorDao()
                dao.setCursor(SyncCursorEntity("tags", 99L))
                val catchUp = client(mockClient(urls), dao)

                val result = catchUp.fetchTransient(tagHandler(seen), TargetedFetch.ByIds(listOf("a", "b")))

                result.shouldBeInstanceOf<AppResult.Success<Set<String>>>()
                result.data shouldContainExactlyInAnyOrder setOf("a", "b")
                seen.map { it.id } shouldContainExactlyInAnyOrder listOf("a", "b")
                urls.single().encodedPath shouldBe "/api/v1/sync/tags"
                urls.single().parameters["ids"] shouldBe "a,b"
                // The persisted cursor is never touched by a transient fetch.
                dao.getCursor("tags") shouldBe 99L
            }
        }

        test("ByCollectionIds hits ?collectionIds=") {
            runTest {
                val urls = mutableListOf<Url>()
                val catchUp = client(mockClient(urls), InMemoryCursorDao())

                catchUp.fetchTransient(tagHandler(mutableListOf()), TargetedFetch.ByCollectionIds(listOf("c1")))

                urls.single().encodedPath shouldBe "/api/v1/sync/tags"
                urls.single().parameters["collectionIds"] shouldBe "c1"
            }
        }

        test("a scope over the per-request cap is chunked, never truncated") {
            runTest {
                val urls = mutableListOf<Url>()
                val seen = mutableListOf<Tag>()
                val catchUp = client(mockClient(urls), InMemoryCursorDao())
                val ids = (1..250).map { "id$it" }

                val result = catchUp.fetchTransient(tagHandler(seen), TargetedFetch.ByIds(ids))

                result.shouldBeInstanceOf<AppResult.Success<Set<String>>>()
                // 250 ids / 100-per-request cap = 3 requests; every id comes back (no truncation).
                urls.size shouldBe 3
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

    override suspend fun all(): List<SyncCursorEntity> =
        cursors.map { (domain, rev) -> SyncCursorEntity(domainName = domain, revision = rev) }

    override suspend fun deleteAll() {
        cursors.clear()
    }
}
