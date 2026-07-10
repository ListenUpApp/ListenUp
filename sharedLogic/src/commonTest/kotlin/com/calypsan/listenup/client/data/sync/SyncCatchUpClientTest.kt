package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.client.test.db.passThroughTransactionRunner
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.error.AppError
import com.calypsan.listenup.api.sync.DomainList
import com.calypsan.listenup.api.sync.Page
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.data.local.db.SyncCursorDao
import com.calypsan.listenup.client.data.local.db.SyncCursorEntity
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
 * Verifies [SyncCatchUpClient] drains REST catch-up pages until `hasMore == false`,
 * advances the per-domain cursor incrementally, routes tombstones via [Tombstoned],
 * and iterates every domain in [ClientSyncDomainRegistry] for `catchUpAll`.
 *
 * Uses a `MockEngine`-backed `HttpClient` and a fake `SyncCursorDao` so the test
 * runs in commonTest with no Room dependency.
 */
class SyncCatchUpClientTest :
    FunSpec({

        fun mockClient(handler: (path: String, since: Long?) -> String): HttpClient =
            HttpClient(
                MockEngine { req ->
                    val path = req.url.encodedPath
                    val since = req.url.parameters["since"]?.toLongOrNull()
                    respond(
                        content = handler(path, since),
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                },
            ) {
                install(ContentNegotiation) { json(contractJson) }
            }

        fun tagHandler(seenItems: MutableList<Pair<Tag, Boolean>>): SyncDomainHandler<Tag> =
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
                    seenItems += item to isTombstone
                    return AppResult.Success(Unit)
                }

                override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = emptyList()
            }

        test("catchUp drains all pages until hasMore is false; cursor advances per page") {
            runTest {
                val seenItems = mutableListOf<Pair<Tag, Boolean>>()
                val handler = tagHandler(seenItems)
                val store = SyncCursorStore(InMemorySyncCursorDao())
                val httpClient =
                    mockClient { _, since ->
                        when (since) {
                            0L -> {
                                contractJson.encodeToString(
                                    Page.serializer(Tag.serializer()),
                                    Page(
                                        items =
                                            listOf(
                                                Tag("a", "alpha", "alpha", 1L, 100L),
                                                Tag("b", "beta", "beta", 2L, 200L),
                                            ),
                                        nextCursor = 2L,
                                        hasMore = true,
                                    ),
                                )
                            }

                            2L -> {
                                contractJson.encodeToString(
                                    Page.serializer(Tag.serializer()),
                                    Page(
                                        items = listOf(Tag("c", "gamma", "gamma", 3L, 300L, deletedAt = 300L)),
                                        nextCursor = 3L,
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

                val result = catchUp.catchUp(handler)

                result.shouldBeInstanceOf<AppResult.Success<Unit>>()
                seenItems.map { it.first.id } shouldContainExactly listOf("a", "b", "c")
                seenItems.last().second shouldBe true
                store.getCursor("tags") shouldBe 3L
            }
        }

        test("tombstone item triggers handler.onCatchUpItem with isTombstone = true") {
            runTest {
                val seenItems = mutableListOf<Pair<Tag, Boolean>>()
                val handler = tagHandler(seenItems)
                val store = SyncCursorStore(InMemorySyncCursorDao())
                val httpClient =
                    mockClient { _, _ ->
                        contractJson.encodeToString(
                            Page.serializer(Tag.serializer()),
                            Page(
                                items =
                                    listOf(
                                        Tag("alive", "current", "current", 10L, 1000L, deletedAt = null),
                                        Tag("dead", "removed", "removed", 11L, 1100L, deletedAt = 1100L),
                                    ),
                                nextCursor = 11L,
                                hasMore = false,
                            ),
                        )
                    }
                val catchUp =
                    SyncCatchUpClient(
                        httpClientProvider = { httpClient },
                        serverUrlProvider = { "http://test" },
                        store = store,
                        transactionRunner = passThroughTransactionRunner(),
                    )

                catchUp.catchUp(handler).shouldBeInstanceOf<AppResult.Success<Unit>>()

                seenItems.map { it.second } shouldContainExactly listOf(false, true)
            }
        }

        test("domains() returns the server's DomainList") {
            runTest {
                val httpClient =
                    mockClient { _, _ ->
                        contractJson.encodeToString(
                            DomainList.serializer(),
                            DomainList(listOf("books", "tags")),
                        )
                    }
                val catchUp =
                    SyncCatchUpClient(
                        httpClientProvider = { httpClient },
                        serverUrlProvider = { "http://test" },
                        store = SyncCursorStore(InMemorySyncCursorDao()),
                        transactionRunner = passThroughTransactionRunner(),
                    )

                val result = catchUp.domains()

                result.shouldBeInstanceOf<AppResult.Success<List<String>>>()
                result.data shouldContainExactly listOf("books", "tags")
            }
        }

        test("catchUpAll iterates every registered domain") {
            runTest {
                val seenDomains = mutableListOf<String>()
                val tagsHandler =
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
                        ): AppResult<Unit> = AppResult.Success(Unit)

                        override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = emptyList()
                    }
                val booksHandler =
                    object : SyncDomainHandler<Tag> {
                        override val domainName = "books"
                        override val payloadSerializer = Tag.serializer()

                        override fun syncId(item: Tag): String = item.id

                        override suspend fun onEvent(
                            event: SyncEvent<Tag>,
                            isOwnEcho: Boolean,
                        ): AppResult<Unit> = AppResult.Success(Unit)

                        override suspend fun onCatchUpItem(
                            item: Tag,
                            isTombstone: Boolean,
                        ): AppResult<Unit> = AppResult.Success(Unit)

                        override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = emptyList()
                    }
                val registry = ClientSyncDomainRegistry()
                registry.register(tagsHandler)
                registry.register(booksHandler)

                val httpClient =
                    mockClient { path, _ ->
                        // path is e.g. "/api/v1/sync/tags" — record the domain segment.
                        seenDomains += path.substringAfterLast('/')
                        contractJson.encodeToString(
                            Page.serializer(Tag.serializer()),
                            Page(items = emptyList<Tag>(), nextCursor = null, hasMore = false),
                        )
                    }
                val catchUp =
                    SyncCatchUpClient(
                        httpClientProvider = { httpClient },
                        serverUrlProvider = { "http://test" },
                        store = SyncCursorStore(InMemorySyncCursorDao()),
                        transactionRunner = passThroughTransactionRunner(),
                    )

                catchUp.catchUpAll(registry).shouldBeInstanceOf<AppResult.Success<Unit>>()

                // Registry sorts domain names alphabetically.
                seenDomains shouldContainExactly listOf("books", "tags")
            }
        }

        test("catchUpAll forwards each domain's typed failure to the report seam and completes every domain") {
            runTest {
                val unauthorized401Client =
                    HttpClient(MockEngine { respond("unauthorized", status = HttpStatusCode.Unauthorized) }) {
                        expectSuccess = true
                    }

                fun minimalHandler(name: String): SyncDomainHandler<Tag> =
                    object : SyncDomainHandler<Tag> {
                        override val domainName = name
                        override val payloadSerializer = Tag.serializer()

                        override fun syncId(item: Tag): String = item.id

                        override suspend fun onEvent(
                            event: SyncEvent<Tag>,
                            isOwnEcho: Boolean,
                        ): AppResult<Unit> = AppResult.Success(Unit)

                        override suspend fun onCatchUpItem(
                            item: Tag,
                            isTombstone: Boolean,
                        ): AppResult<Unit> = AppResult.Success(Unit)

                        override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>>? = null
                    }

                val registry = ClientSyncDomainRegistry()
                registry.register(minimalHandler("tags"))
                registry.register(minimalHandler("genres"))
                val reported = mutableListOf<AppError>()
                val client =
                    SyncCatchUpClient(
                        httpClientProvider = { unauthorized401Client },
                        serverUrlProvider = { "http://test" },
                        store = SyncCursorStore(InMemorySyncCursorDao()),
                        transactionRunner = passThroughTransactionRunner(),
                        reportConnectionIssue = { reported += it },
                    )

                client.catchUpAll(registry)

                // Both domains were attempted (loop never aborts) and both failures surfaced typed.
                reported.map { it.code } shouldContainExactly listOf("AUTH_SESSION_EXPIRED", "AUTH_SESSION_EXPIRED")
            }
        }
    })

/**
 * In-memory [SyncCursorDao] for tests — sidesteps Room so the suite runs in
 * commonTest. Mirrors the DAO contract's behavior: get returns null for
 * unknown domains, set is upsert.
 */
private class InMemorySyncCursorDao : SyncCursorDao {
    private val cursors = mutableMapOf<String, Long>()

    override suspend fun getCursor(domainName: String): Long? = cursors[domainName]

    override suspend fun setCursor(entity: SyncCursorEntity) {
        cursors[entity.domainName] = entity.revision
    }

    override suspend fun all(): List<SyncCursorEntity> = cursors.map { (domain, rev) -> SyncCursorEntity(domainName = domain, revision = rev) }

    override suspend fun deleteAll() {
        cursors.clear()
    }
}
