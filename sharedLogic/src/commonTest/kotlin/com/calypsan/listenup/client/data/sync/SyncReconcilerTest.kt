package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.local.db.SyncCursorDao
import com.calypsan.listenup.client.data.local.db.SyncCursorEntity
import com.calypsan.listenup.api.result.AppResult
import dev.mokkery.answering.returns
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verify.VerifyMode
import dev.mokkery.verifySuspend
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer

class SyncReconcilerTest :
    FunSpec({

        /**
         * Minimal fake handler: [localDigestRows] returns [rows], or null to signal opt-out.
         * All other interface methods are no-op stubs — the reconciler only calls
         * [SyncDomainHandler.domainName] + [SyncDomainHandler.localDigestRows].
         */
        fun fakeHandler(
            name: String,
            rows: List<Pair<String, Long>>?,
        ): SyncDomainHandler<Tag> =
            object : SyncDomainHandler<Tag> {
                override val domainName = name
                override val payloadSerializer: KSerializer<Tag> = Tag.serializer()

                override fun syncId(item: Tag): String = item.id

                override suspend fun onEvent(
                    event: SyncEvent<Tag>,
                    isOwnEcho: Boolean,
                ): AppResult<Unit> = AppResult.Success(Unit)

                override suspend fun onCatchUpItem(
                    item: Tag,
                    isTombstone: Boolean,
                ): AppResult<Unit> = AppResult.Success(Unit)

                override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>>? = rows
            }

        /**
         * In-memory [SyncCursorStore]. A single "__seed__" entry drives [SyncCursorStore.highestCursor].
         */
        fun inMemoryStore(highestRevision: Long?): SyncCursorStore {
            val dao =
                object : SyncCursorDao {
                    private val cursors = mutableMapOf<String, Long>()

                    init {
                        if (highestRevision != null) {
                            cursors["__seed__"] = highestRevision
                        }
                    }

                    override suspend fun getCursor(domainName: String): Long? = cursors[domainName]

                    override suspend fun setCursor(entity: SyncCursorEntity) {
                        cursors[entity.domainName] = entity.revision
                    }

                    override suspend fun all(): List<SyncCursorEntity> = cursors.map { (domain, rev) -> SyncCursorEntity(domainName = domain, revision = rev) }

                    override suspend fun deleteAll() {
                        cursors.clear()
                    }
                }
            return SyncCursorStore(dao)
        }

        /**
         * Builds a [DomainDigestClient] backed by an in-memory map: each GET to
         * `/api/v1/sync/<domain>/digest?cursor=…` responds with the pre-set [DomainDigest]
         * for that domain, or 404 if the domain is absent.
         */
        fun fakeDigestClient(domainDigests: Map<String, DomainDigest>): DomainDigestClient {
            val mockClient =
                HttpClient(
                    MockEngine { req ->
                        // URL: /api/v1/sync/<domain>/digest  →  segments[-2] = domain
                        val domain =
                            req.url.pathSegments
                                .dropLast(1)
                                .last()
                        val digest = domainDigests[domain]
                        if (digest != null) {
                            respond(
                                content = contractJson.encodeToString(DomainDigest.serializer(), digest),
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        } else {
                            respond(
                                content = "not found",
                                status = io.ktor.http.HttpStatusCode.NotFound,
                            )
                        }
                    },
                ) { install(ContentNegotiation) { json(contractJson) } }
            return DomainDigestClient(
                httpClientProvider = { mockClient },
                serverUrlProvider = { "http://test" },
            )
        }

        // ---------------------------------------------------------------------------------
        // (a) matching digest → not re-pulled; mismatching digest → re-pulled
        // ---------------------------------------------------------------------------------

        test("(a) matching digest is NOT re-pulled; mismatching digest IS re-pulled via catchUpFromZero") {
            runTest {
                val max = 100L

                // "books": local rows match server digest — no re-pull expected.
                val bookRows = listOf("b1" to 1L)
                val bookDigest = DigestComputer.compute(max, bookRows) // identical rows → match

                // "tags": local rows differ from the server's digest — re-pull expected.
                val tagRows = listOf("t1" to 5L)
                val tagDigest = DigestComputer.compute(max, listOf("t1" to 999L)) // server has different rev → mismatch

                val bookHandler = fakeHandler("books", bookRows)
                val tagHandler = fakeHandler("tags", tagRows)

                val registry = ClientSyncDomainRegistry()
                registry.register(bookHandler)
                registry.register(tagHandler)

                val store = inMemoryStore(max)
                val digestClient =
                    fakeDigestClient(mapOf("books" to bookDigest, "tags" to tagDigest))
                val catchUp = mock<CatchUp>()
                everySuspend { catchUp.catchUpFromZero(any<SyncDomainHandler<Any>>()) } returns AppResult.Success(Unit)

                SyncReconciler(registry, store, digestClient, catchUp).reconcileAll()

                // "books" matched → catchUpFromZero must NOT have been called for it.
                verifySuspend(VerifyMode.not) { catchUp.catchUpFromZero(bookHandler) }

                // "tags" mismatched → catchUpFromZero MUST have been called for it.
                verifySuspend { catchUp.catchUpFromZero(tagHandler) }
            }
        }

        // ---------------------------------------------------------------------------------
        // (b) no-op when highestCursor is null
        // ---------------------------------------------------------------------------------

        test("(b) reconcileAll is a no-op when highestCursor is null") {
            runTest {
                val registry = ClientSyncDomainRegistry()
                registry.register(fakeHandler("books", listOf("b1" to 1L)))

                val store = inMemoryStore(null) // highestCursor → null
                val digestClient = fakeDigestClient(emptyMap())
                val catchUp = mock<CatchUp>()

                SyncReconciler(registry, store, digestClient, catchUp).reconcileAll()

                // No cursor → neither a digest fetch nor a re-pull must occur.
                verifySuspend(VerifyMode.not) { catchUp.catchUpFromZero(any<SyncDomainHandler<Any>>()) }
            }
        }

        // ---------------------------------------------------------------------------------
        // (c) domain that returns null from localDigestRows is skipped
        // ---------------------------------------------------------------------------------

        test("(c) domain whose localDigestRows returns null is skipped (no fetch, no re-pull)") {
            runTest {
                val max = 50L

                val seriesHandler = fakeHandler("series", null) // opt-out
                val registry = ClientSyncDomainRegistry()
                registry.register(seriesHandler)

                val store = inMemoryStore(max)
                // No "series" entry in the digest map, but this path should never be reached.
                val digestClient = fakeDigestClient(emptyMap())
                val catchUp = mock<CatchUp>()

                SyncReconciler(registry, store, digestClient, catchUp).reconcileAll()

                verifySuspend(VerifyMode.not) { catchUp.catchUpFromZero(any<SyncDomainHandler<Any>>()) }
            }
        }

        // ---------------------------------------------------------------------------------
        // (d) an access-gated handler that drifted is re-derived + pruned, NOT upsert-only re-pulled
        // ---------------------------------------------------------------------------------

        test("(d) drifted AccessFilteredSyncHandler prunes to the accessible set instead of catchUpFromZero") {
            runTest {
                val max = 100L

                // "collections" is access-gated. Local digest diverges from the server's, so the
                // reconciler must re-derive the accessible set and prune — an upsert-only re-pull
                // could never remove a revoked row, so the digest would never converge.
                val localRows = listOf("c1" to 5L, "c2" to 5L)
                // Server digest counts only c1 — c2's share was revoked. Divergence → drift.
                val serverDigest = DigestComputer.compute(max, listOf("c1" to 5L))

                val prunedTo = mutableListOf<Set<String>>()
                val accessibleFromServer = setOf("c1")
                val handler = accessGatedHandler("collections", localRows, prunedTo)

                val registry = ClientSyncDomainRegistry()
                registry.register(handler)

                val store = inMemoryStore(max)
                val digestClient = fakeDigestClient(mapOf("collections" to serverDigest))
                val catchUp = mock<CatchUp>()
                everySuspend { catchUp.catchUpTransient(any<SyncDomainHandler<Any>>()) } returns
                    AppResult.Success(accessibleFromServer)

                SyncReconciler(registry, store, digestClient, catchUp).reconcileAll()

                // The upsert-only path must NOT be taken for an access-gated domain.
                verifySuspend(VerifyMode.not) { catchUp.catchUpFromZero(any<SyncDomainHandler<Any>>()) }
                // The transient re-derive drove a prune to exactly the accessible set.
                verifySuspend { catchUp.catchUpTransient(handler) }
                prunedTo shouldContainExactly listOf(accessibleFromServer)
            }
        }
    })

/**
 * Fake handler that is BOTH a [SyncDomainHandler] and an [AccessFilteredSyncHandler] — the shape of
 * the four access-gated domains (books + the three collection domains). Records every [pruneTo]
 * call's accessible set so the test can assert the reconciler routed drift through the prune path.
 */
private fun accessGatedHandler(
    name: String,
    rows: List<Pair<String, Long>>,
    prunedTo: MutableList<Set<String>>,
): SyncDomainHandler<Tag> =
    object : SyncDomainHandler<Tag>, AccessFilteredSyncHandler {
        override val domainName = name
        override val payloadSerializer: KSerializer<Tag> = Tag.serializer()

        override fun syncId(item: Tag): String = item.id

        override suspend fun onEvent(
            event: SyncEvent<Tag>,
            isOwnEcho: Boolean,
        ): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun onCatchUpItem(
            item: Tag,
            isTombstone: Boolean,
        ): AppResult<Unit> = AppResult.Success(Unit)

        override suspend fun localDigestRows(maxRevision: Long): List<Pair<String, Long>> = rows

        override suspend fun localLiveIds(): Set<String> = rows.map { it.first }.toSet()

        override suspend fun pruneTo(
            accessibleIds: Set<String>,
            now: Long,
        ) {
            prunedTo += accessibleIds
        }

        override suspend fun pruneWithin(
            candidateIds: Set<String>,
            accessibleIds: Set<String>,
            now: Long,
        ) {
            prunedTo += (candidateIds intersect accessibleIds)
        }
    }
