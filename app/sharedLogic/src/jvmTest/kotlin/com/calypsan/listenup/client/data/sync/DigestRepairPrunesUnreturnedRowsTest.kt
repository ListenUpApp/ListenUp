package com.calypsan.listenup.client.data.sync

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.DomainDigest
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.ListeningEventEntity
import com.calypsan.listenup.client.data.local.db.RoomTransactionRunner
import com.calypsan.listenup.client.data.local.db.TagEntity
import com.calypsan.listenup.client.data.remote.RpcChannel
import com.calypsan.listenup.client.data.remote.forTest
import com.calypsan.listenup.client.data.sync.domains.OutboxInFlightQuery
import com.calypsan.listenup.client.data.sync.domains.listeningEventsDomain
import com.calypsan.listenup.client.data.sync.domains.tagsDomain
import com.calypsan.listenup.client.data.sync.domains.toHandler
import com.calypsan.listenup.client.test.db.createInMemoryTestDatabase
import com.calypsan.listenup.client.test.fake.FakeAuthSession
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import kotlinx.coroutines.test.runTest

private const val THROUGH = 50L

/**
 * A digest repair re-pulls a drifted domain from zero. The pull returns every row the server holds, so
 * a live local row it did not return is one the server no longer has — after a restore, a row created
 * after the backup — and the repair removes it. Otherwise the digest never matches and the domain is
 * re-pulled on every connect. Only rows the server once stamped are removed: an unacknowledged row
 * (revision 0), a row with a local write still queued, and a row newer than the pull all stay.
 */
class DigestRepairPrunesUnreturnedRowsTest :
    FunSpec({

        test("a drifted domain drops the live rows its from-zero pull did not return") {
            withTags(local = listOf("kept" to 3L, "gone" to 4L)) { db, reconcile ->
                reconcile(FromZeroPull.Complete(liveIds = setOf("kept"), throughRevision = THROUGH))
                db.liveTagIds() shouldContainExactlyInAnyOrder listOf("kept")
            }
        }

        test("a row with a local write still queued survives the repair") {
            withTags(local = listOf("kept" to 3L, "queued" to 4L), queued = setOf("queued")) { db, reconcile ->
                reconcile(FromZeroPull.Complete(liveIds = setOf("kept"), throughRevision = THROUGH))
                db.liveTagIds() shouldContainExactlyInAnyOrder listOf("kept", "queued")
            }
        }

        test("an unacknowledged row and a row newer than the pull survive the repair") {
            withTags(local = listOf("kept" to 3L, "unacked" to 0L, "newer" to THROUGH + 1)) { db, reconcile ->
                reconcile(FromZeroPull.Complete(liveIds = setOf("kept"), throughRevision = THROUGH))
                db.liveTagIds() shouldContainExactlyInAnyOrder listOf("kept", "unacked", "newer")
            }
        }

        test("an incomplete pull removes nothing") {
            withTags(local = listOf("kept" to 3L, "gone" to 4L)) { db, reconcile ->
                reconcile(FromZeroPull.Incomplete)
                db.liveTagIds() shouldContainExactlyInAnyOrder listOf("kept", "gone")
            }
        }

        test("listening events the restored server never had leave the device") {
            runTest {
                val db = createInMemoryTestDatabase()
                try {
                    db.listeningEventDao().upsert(listeningEvent("kept", revision = 3))
                    db.listeningEventDao().upsert(listeningEvent("gone", revision = 4))
                    val handler =
                        listeningEventsDomain(db, FakeAuthSession(userId = "u1"))
                            .toHandler(RoomTransactionRunner(db), ClientSyncDomainRegistry())
                    reconcileDrifted(db, handler, FromZeroPull.Complete(setOf("kept"), THROUGH))
                    db.listeningEventDao().digestRows(Long.MAX_VALUE).map { it.id } shouldContainExactlyInAnyOrder
                        listOf("kept")
                } finally {
                    db.close()
                }
            }
        }
    })

private suspend fun ListenUpDatabase.liveTagIds(): List<String> = tagDao().digestRows(Long.MAX_VALUE).map { it.id }

private fun withTags(
    local: List<Pair<String, Long>>,
    queued: Set<String> = emptySet(),
    block: suspend (ListenUpDatabase, suspend (FromZeroPull) -> Unit) -> Unit,
) = runTest {
    val db = createInMemoryTestDatabase()
    try {
        local.forEach { (id, revision) ->
            db.tagDao().upsert(TagEntity(id = id, name = id, slug = id, revision = revision, updatedAt = 1L))
        }
        val handler =
            tagsDomain(db).toHandler(
                RoomTransactionRunner(db),
                ClientSyncDomainRegistry(),
                inFlightOutbox = OutboxInFlightQuery { domain, id -> domain == "tags" && id in queued },
            )
        block(db) { pull -> reconcileDrifted(db, handler, pull) }
    } finally {
        db.close()
    }
}

/** Runs one reconcile in which [handler]'s domain has drifted and its from-zero pull reports [pull]. */
private suspend fun reconcileDrifted(
    db: ListenUpDatabase,
    handler: SyncDomainHandler<*>,
    pull: FromZeroPull,
) {
    val registry = ClientSyncDomainRegistry().apply { register(handler) }
    val store = SyncCursorStore(db.syncCursorDao()).apply { setCursor(handler.domainName, THROUGH) }
    val alwaysDrifted =
        object : FakeSyncStreamService() {
            override suspend fun digest(
                domain: String,
                cursor: Long,
            ): AppResult<DomainDigest> = AppResult.Success(DomainDigest(cursor = cursor, count = -1, hash = "server"))
        }
    SyncReconciler(registry, store, DomainDigestClient(RpcChannel.forTest(alwaysDrifted)), ReportingCatchUp(pull))
        .reconcileAll()
}

/** A [CatchUp] whose every from-zero pull reports [pull]; nothing else is reached by a reconcile. */
private class ReportingCatchUp(
    private val pull: FromZeroPull,
) : CatchUp {
    override suspend fun <T : Any> catchUp(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpFromZero(handler: SyncDomainHandler<T>): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpFromZeroReporting(handler: SyncDomainHandler<T>): AppResult<FromZeroPull> =
        AppResult.Success(pull)

    override suspend fun catchUpAll(registry: ClientSyncDomainRegistry): AppResult<Unit> = AppResult.Success(Unit)

    override suspend fun <T : Any> catchUpTransient(handler: SyncDomainHandler<T>): AppResult<Set<String>> = AppResult.Success(emptySet())

    override suspend fun domains(): AppResult<List<String>> = AppResult.Success(emptyList())
}

private fun listeningEvent(
    id: String,
    revision: Long,
) = ListeningEventEntity(
    id = id,
    userId = "u1",
    bookId = "book-1",
    startPositionMs = 0,
    endPositionMs = 1_000,
    startedAt = 1L,
    endedAt = 2L,
    playbackSpeed = 1f,
    tz = "UTC",
    deviceLabel = null,
    revision = revision,
    deletedAt = null,
)
