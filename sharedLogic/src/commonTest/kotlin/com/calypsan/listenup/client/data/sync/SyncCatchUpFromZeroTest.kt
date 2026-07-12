package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.local.db.SyncCursorDao
import com.calypsan.listenup.client.data.local.db.SyncCursorEntity
import com.calypsan.listenup.client.test.db.passThroughTransactionRunner
import com.calypsan.listenup.api.result.AppResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest

/**
 * Verifies that [SyncCatchUpClient.catchUpFromZero] always starts from `since = 0`,
 * regardless of the persisted cursor value, and advances the cursor after each page.
 */
class SyncCatchUpFromZeroTest :
    FunSpec({

        fun mockClient(handler: (since: Long?) -> String): HttpClient =
            HttpClient(
                MockEngine { req ->
                    val since = req.url.parameters["since"]?.toLongOrNull()
                    respond(
                        content = handler(since),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                },
            ) {
                install(ContentNegotiation) { json(contractJson) }
            }

        fun tagHandler(seenItems: MutableList<Tag>): SyncDomainHandler<Tag> =
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
                    seenItems += item
                    return AppResult.Success(Unit)
                }

                override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = emptyList()
            }

        test("catchUpFromZero sends since=0 even when cursor is pre-seeded to a non-zero value") {
            runTest {
                val seenItems = mutableListOf<Tag>()
                val handler = tagHandler(seenItems)

                // Pre-seed the cursor store with a non-zero value for this domain.
                val dao = InMemoryDao()
                dao.setCursor(SyncCursorEntity(domainName = "tags", revision = 500L))
                val store = SyncCursorStore(dao)

                val sinceValues = mutableListOf<Long?>()
                val httpClient =
                    mockClient { since ->
                        sinceValues += since
                        when (since) {
                            0L -> {
                                contractJson.encodeToString(
                                    Page.serializer(Tag.serializer()),
                                    Page(
                                        items =
                                            listOf(
                                                Tag("x", "ex", "ex", 1L, 10L),
                                                Tag("y", "why", "why", 2L, 20L),
                                            ),
                                        nextCursor = 2L,
                                        hasMore = false,
                                    ),
                                )
                            }

                            else -> {
                                error("unexpected since=$since")
                            }
                        }
                    }

                val catchUp =
                    SyncCatchUpClient(
                        httpClientProvider = { httpClient },
                        serverUrlProvider = { "http://test" },
                        store = store,
                        transactionRunner = passThroughTransactionRunner(),
                    )

                val result = catchUp.catchUpFromZero(handler)

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                // First (and only) request must have used since=0, not the pre-seeded 500.
                sinceValues.first() shouldBe 0L
                // Both items were applied.
                seenItems.map { it.id } shouldContainExactly listOf("x", "y")
                // Cursor was advanced to the page's nextCursor.
                store.getCursor("tags") shouldBe 2L
            }
        }
    })

/** Minimal in-memory [SyncCursorDao] for this test — mirrors the one in [SyncCatchUpClientTest]. */
private class InMemoryDao : SyncCursorDao {
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
