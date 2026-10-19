package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.error.AppError
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.Flow
import com.calypsan.listenup.api.error.AuthError
import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.client.data.local.db.SyncCursorDao
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.domains.AccessDeltaPolicy
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
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
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
            hasBackstop: Boolean = true,
        ): SyncDomainHandler<Tag> =
            object : SyncDomainHandler<Tag> {
                override val domainName = name
                override val hasDigestBackstop = hasBackstop
                override val payloadSerializer: KSerializer<Tag> = Tag.serializer()

                override fun syncId(item: Tag): String = item.id

                override suspend fun onEvent(event: SyncEvent<Tag>): AppResult<Unit> = AppResult.Success(Unit)

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

                    override suspend fun setCursorMonotonic(
                        domainName: String,
                        revision: Long,
                    ) {
                        val current = cursors[domainName]
                        if (current == null || revision > current) cursors[domainName] = revision
                    }

                    override suspend fun advanceExistingCursor(
                        domainName: String,
                        revision: Long,
                    ) {
                        val current = cursors[domainName] ?: return
                        if (revision > current) cursors[domainName] = revision
                    }

                    override fun observeCursor(domainName: String): Flow<Long?> = flowOf(cursors[domainName])

                    override suspend fun all(): List<SyncCursorEntity> =
                        cursors.map {
                            (
                                domain,
                                rev,
                            ),
                            ->
                            SyncCursorEntity(
                                domainName = domain,
                                revision = rev,
                            )
                        }

                    override suspend fun deleteAll() {
                        cursors.clear()
                    }
                }
            return SyncCursorStore(dao)
        }

        /**
         * Builds a [DomainDigestClient] backed by an in-memory map: [SyncStreamService.digest]
         * for a domain present in [domainDigests] returns that pre-set [DomainDigest]; a domain
         * absent from the map fails with [SyncError.UnknownDomain] — the typed equivalent of the
         * old REST route's 404.
         */
        fun fakeDigestClient(domainDigests: Map<String, DomainDigest>): DomainDigestClient {
            val service =
                object : FakeSyncStreamService() {
                    override suspend fun digest(
                        domain: String,
                        cursor: Long,
                    ): AppResult<DomainDigest> {
                        val digest = domainDigests[domain]
                        return if (digest != null) {
                            AppResult.Success(digest)
                        } else {
                            AppResult.Failure(SyncError.UnknownDomain(domain = domain))
                        }
                    }
                }
            return DomainDigestClient(channel = RpcChannel.forTest(service))
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
        // requestUnbackedRepull: an import writes rows without live events; a digest-less domain's
        // cursor may already be past them and nothing else would fetch them.
        // ---------------------------------------------------------------------------------

        test("requestUnbackedRepull re-reads only the digest-less domain from zero, once") {
            runTest {
                val max = 100L
                val rows = listOf("b1" to 1L)
                val positions = fakeHandler("playback_positions", rows = null, hasBackstop = false)
                val books = fakeHandler("books", rows = rows)
                val registry = ClientSyncDomainRegistry()
                registry.register(positions)
                registry.register(books)
                val digestClient = fakeDigestClient(mapOf("books" to DigestComputer.compute(max, rows)))
                val catchUp = RepullRecordingCatchUp()
                val reconciler = SyncReconciler(registry, inMemoryStore(max), digestClient, catchUp)

                reconciler.requestUnbackedRepull()
                reconciler.reconcileAll()
                catchUp.fromZero shouldContainExactly listOf("playback_positions")

                // The request is consumed: a second pass with no new request re-reads nothing.
                reconciler.reconcileAll()
                catchUp.fromZero shouldContainExactly listOf("playback_positions")
            }
        }

        test("a digest-less domain is not re-read from zero when nothing asked for it") {
            runTest {
                val registry = ClientSyncDomainRegistry()
                registry.register(fakeHandler("playback_positions", rows = null, hasBackstop = false))
                val catchUp = RepullRecordingCatchUp()

                SyncReconciler(registry, inMemoryStore(100L), fakeDigestClient(emptyMap()), catchUp).reconcileAll()

                catchUp.fromZero shouldBe emptyList()
            }
        }

        test("a failed unbacked re-read keeps the request for the next reconcile") {
            runTest {
                val registry = ClientSyncDomainRegistry()
                registry.register(fakeHandler("playback_positions", rows = null, hasBackstop = false))
                val catchUp = RepullRecordingCatchUp(failFromZero = true)
                val reconciler = SyncReconciler(registry, inMemoryStore(100L), fakeDigestClient(emptyMap()), catchUp)

                reconciler.requestUnbackedRepull()
                reconciler.reconcileAll()
                catchUp.failFromZero = false
                reconciler.reconcileAll()

                catchUp.fromZero shouldContainExactly listOf("playback_positions", "playback_positions")
            }
        }

        test("the unbacked re-read request survives a reconcile that has no cursor yet") {
            runTest {
                val registry = ClientSyncDomainRegistry()
                registry.register(fakeHandler("playback_positions", rows = null, hasBackstop = false))
                val catchUp = RepullRecordingCatchUp()
                val noCursor = SyncReconciler(registry, inMemoryStore(null), fakeDigestClient(emptyMap()), catchUp)

                noCursor.requestUnbackedRepull()
                noCursor.reconcileAll()
                catchUp.fromZero shouldBe emptyList()
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

        test("digest fetch failure forwards the typed error to the report seam and still completes") {
            runTest {
                val registry = ClientSyncDomainRegistry()
                registry.register(fakeHandler("tags", rows = listOf("a" to 1L)))
                // A business AppResult.Failure returned by the service passes through the channel
                // untouched (RpcChannel.call), mirroring the production shape of an auth-expiry
                // digest fetch surfacing as a typed AuthError.SessionExpired.
                val unauthorizedService =
                    object : FakeSyncStreamService() {
                        override suspend fun digest(
                            domain: String,
                            cursor: Long,
                        ): AppResult<DomainDigest> = AppResult.Failure(AuthError.SessionExpired())
                    }
                val failingDigestClient = DomainDigestClient(channel = RpcChannel.forTest(unauthorizedService))
                val reported = mutableListOf<AppError>()
                val reconciler =
                    SyncReconciler(
                        registry = registry,
                        store = inMemoryStore(highestRevision = 5L),
                        digestClient = failingDigestClient,
                        catchUp = mock<CatchUp>(), // must never be called — drift is unknown, not detected
                        reportConnectionIssue = { reported += it },
                    )

                reconciler.reconcileAll() // completes without throwing

                reported.map { it.code } shouldContainExactly listOf("AUTH_SESSION_EXPIRED")
            }
        }

        test("drifted domains are repaired one at a time") {
            runTest {
                val max = 100L
                val names = listOf("books", "tags", "genres")
                val registry = ClientSyncDomainRegistry()
                names.forEach { registry.register(fakeHandler(it, rows = listOf("x" to 1L))) }
                // Every server digest differs from the local rows, so every domain has drifted.
                val digestClient =
                    fakeDigestClient(names.associateWith { DigestComputer.compute(max, listOf("x" to 999L)) })
                val catchUp = OverlapTrackingCatchUp()

                SyncReconciler(registry, inMemoryStore(max), digestClient, catchUp).reconcileAll()

                catchUp.repaired shouldContainExactlyInAnyOrder names
                catchUp.maxInFlight shouldBe 1
            }
        }

        test("digests are still compared for every domain when one comparison fails") {
            runTest {
                val max = 100L
                val registry = ClientSyncDomainRegistry()
                // "unknown" has no entry in the digest map, so its comparison fails.
                listOf("books", "unknown", "tags").forEach { registry.register(fakeHandler(it, rows = listOf("x" to 1L))) }
                val drifted = DigestComputer.compute(max, listOf("x" to 999L))
                val digestClient = fakeDigestClient(mapOf("books" to drifted, "tags" to drifted))
                val catchUp = OverlapTrackingCatchUp()

                SyncReconciler(registry, inMemoryStore(max), digestClient, catchUp).reconcileAll()

                catchUp.repaired shouldContainExactlyInAnyOrder listOf("books", "tags")
            }
        }
    })

/**
 * [CatchUp] whose from-zero repair suspends (a real yield, so overlapping repairs can interleave on a
 * test dispatcher) and records the peak number of repairs in flight.
 */
private class OverlapTrackingCatchUp : CatchUp {
    val repaired = mutableListOf<String>()
    var maxInFlight = 0
    private var inFlight = 0

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> {
        inFlight++
        maxInFlight = maxOf(maxInFlight, inFlight)
        try {
            yield()
            repaired += handler.domainName
        } finally {
            inFlight--
        }
        return AppResult.Success(Unit)
    }

    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun <T : Any> fetchTransient(
        handler: SyncDomainHandler<T>,
        fetch: TargetedFetch,
    ): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}

/**
 * Fake handler that is BOTH a [SyncDomainHandler] and an [AccessFilteredSyncHandler] — the shape of
 * the access-gated domains (books, activities, the three collection domains, and the two
 * book-junction domains). Records every [pruneTo]
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
        override val deltaPolicy: AccessDeltaPolicy = AccessDeltaPolicy.LiveTailOnly("test fake: delta path unused here")

        override fun syncId(item: Tag): String = item.id

        override suspend fun onEvent(event: SyncEvent<Tag>): AppResult<Unit> = AppResult.Success(Unit)

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
            prunedTo += candidateIds intersect accessibleIds
        }
    }

/** [CatchUp] that records every from-zero re-read, optionally failing them. */
private class RepullRecordingCatchUp(
    var failFromZero: Boolean = false,
) : CatchUp {
    val fromZero = mutableListOf<String>()

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> {
        fromZero += handler.domainName
        return if (failFromZero) {
            AppResult.Failure(SyncError.UnknownDomain(domain = handler.domainName))
        } else {
            AppResult.Success(Unit)
        }
    }

    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun <T : Any> fetchTransient(
        handler: SyncDomainHandler<T>,
        fetch: TargetedFetch,
    ): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}
