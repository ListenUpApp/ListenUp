package com.calypsan.listenup.client.data.sync.handlers

import com.calypsan.listenup.api.error.SyncError
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.SyncEvent
import com.calypsan.listenup.core.AppResult
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ChapterId
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.SeriesId
import com.calypsan.listenup.core.Timestamp
import com.calypsan.listenup.core.currentEpochMilliseconds
import com.calypsan.listenup.client.data.local.db.AudioFileEntity
import com.calypsan.listenup.client.data.local.db.BookContributorCrossRef
import com.calypsan.listenup.client.data.local.db.BookEntityMapper
import com.calypsan.listenup.client.data.local.db.BookSeriesCrossRef
import com.calypsan.listenup.client.data.local.db.ChapterEntity
import com.calypsan.listenup.client.data.local.db.ContributorEntity
import com.calypsan.listenup.client.data.local.db.ListenUpDatabase
import com.calypsan.listenup.client.data.local.db.SeriesEntity
import com.calypsan.listenup.client.data.local.db.TransactionRunner
import com.calypsan.listenup.client.data.sync.ClientSyncDomainRegistry
import com.calypsan.listenup.client.data.sync.SyncDomainHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

/**
 * Client-side sync handler for the `books` domain.
 *
 * Applies server sync events into Room as a single atomic aggregate write. Three
 * invariants shape this handler:
 *
 * - **Atomic aggregate upsert.** A book is a root row plus child rows (chapters,
 *   contributors, series memberships, audio files). Every event replaces the whole
 *   aggregate inside one IMMEDIATE write transaction — clients never observe a book
 *   with stale children or a partially-applied update.
 *
 * - **Echo no-flicker.** When an event echoes this client's own write
 *   (`isOwnEcho == true`), the visible fields are already correct locally. The handler
 *   then advances only `revision` and `updatedAt` on the root row, leaving title,
 *   cover, and child rows untouched — repainting them would flicker the UI.
 *
 * - **Enrichment preservation.** Contributor and series rows carry client- and
 *   server-enriched fields (descriptions, images, ASINs) that the book sync payload
 *   does not. `ensureExists` semantics update only the identity fields a book payload
 *   is authoritative for, preserving enrichment already present on the local row.
 *
 * Self-registers in [ClientSyncDomainRegistry] at construction.
 */
class BookSyncDomainHandler(
    private val database: ListenUpDatabase,
    private val mapper: BookEntityMapper,
    private val transactionRunner: TransactionRunner,
    registry: ClientSyncDomainRegistry,
) : SyncDomainHandler<BookSyncPayload> {
    override val domainName: String = "books"
    override val payloadSerializer = BookSyncPayload.serializer()

    init {
        registry.register(this)
    }

    override suspend fun onEvent(
        event: SyncEvent<BookSyncPayload>,
        isOwnEcho: Boolean,
    ): AppResult<Unit> =
        runAtomically(event.id) {
            when (event) {
                is SyncEvent.Created -> {
                    upsertAggregate(event.payload, isOwnEcho)
                }

                is SyncEvent.Updated -> {
                    upsertAggregate(event.payload, isOwnEcho)
                }

                is SyncEvent.Deleted -> {
                    database.bookDao().softDelete(
                        id = BookId(event.id),
                        deletedAt = event.occurredAt,
                        revision = event.revision,
                    )
                }
            }
        }

    override suspend fun onCatchUpItem(
        item: BookSyncPayload,
        isTombstone: Boolean,
    ): AppResult<Unit> =
        runAtomically(item.id) {
            if (isTombstone) {
                database.bookDao().softDelete(
                    id = BookId(item.id),
                    deletedAt = item.deletedAt ?: item.updatedAt,
                    revision = item.revision,
                )
            } else {
                upsertAggregate(item, isOwnEcho = false)
            }
        }

    /**
     * Run [block] inside one IMMEDIATE write transaction, mapping any escaped failure to a
     * typed [SyncError.SyncFailed]. [CancellationException] is re-thrown — cancellation is
     * not a sync failure.
     */
    private suspend fun runAtomically(
        bookId: String,
        block: suspend () -> Unit,
    ): AppResult<Unit> =
        try {
            transactionRunner.atomically { block() }
            AppResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "Failed to apply books sync event for $bookId" }
            AppResult.Failure(SyncError.SyncFailed(debugInfo = "books/$bookId: ${e.message}"))
        }

    /**
     * Upsert the whole book aggregate. On an own-echo for an already-present book, advance
     * only the sync substrate fields and return — visible fields and children are untouched.
     * Otherwise upsert the root row and replace every child collection wholesale.
     */
    private suspend fun upsertAggregate(
        payload: BookSyncPayload,
        isOwnEcho: Boolean,
    ) {
        val bookId = BookId(payload.id)
        val existing = database.bookDao().getById(bookId)

        if (isOwnEcho && existing != null) {
            database.bookDao().updateRevisionAndTimestamp(
                id = bookId,
                revision = payload.revision,
                updatedAt = Timestamp(payload.updatedAt),
            )
            return
        }

        database.bookDao().upsert(mapper.toBookEntity(payload, existing))

        applyContributors(bookId, payload.contributors)
        applySeries(bookId, payload.series)
        applyChapters(bookId, payload.chapters)
        applyAudioFiles(payload.id, payload.audioFiles)
    }

    private suspend fun applyContributors(
        bookId: BookId,
        contributors: List<BookContributorPayload>,
    ) {
        database.bookContributorDao().deleteContributorsForBook(bookId)
        for (contributor in contributors) {
            contributorEnsureExists(contributor)
            database.bookContributorDao().insert(
                BookContributorCrossRef(
                    bookId = bookId,
                    contributorId = ContributorId(contributor.id),
                    role = contributor.role,
                    creditedAs = contributor.creditedAs,
                ),
            )
        }
    }

    private suspend fun applySeries(
        bookId: BookId,
        series: List<BookSeriesPayload>,
    ) {
        database.bookSeriesDao().deleteSeriesForBook(bookId)
        for (entry in series) {
            seriesEnsureExists(entry)
            database.bookSeriesDao().insert(
                BookSeriesCrossRef(
                    bookId = bookId,
                    seriesId = SeriesId(entry.id),
                    sequence = entry.sequence,
                ),
            )
        }
    }

    private suspend fun applyChapters(
        bookId: BookId,
        chapters: List<BookChapterPayload>,
    ) {
        database.chapterDao().deleteChaptersForBook(bookId)
        database.chapterDao().upsertAll(
            chapters.map { chapter ->
                ChapterEntity(
                    id = ChapterId(chapter.id),
                    bookId = bookId,
                    title = chapter.title,
                    duration = chapter.duration,
                    startTime = chapter.startTime,
                )
            },
        )
    }

    private suspend fun applyAudioFiles(
        bookId: String,
        audioFiles: List<BookAudioFilePayload>,
    ) {
        database.audioFileDao().deleteForBook(bookId)
        database.audioFileDao().upsertAll(
            audioFiles.map { file ->
                AudioFileEntity(
                    bookId = BookId(bookId),
                    index = file.index,
                    id = file.id,
                    filename = file.filename,
                    format = file.format,
                    codec = file.codec,
                    duration = file.duration,
                    size = file.size,
                )
            },
        )
    }

    /**
     * Ensure a contributor row exists for [contributor]. When present, update only the
     * identity fields the book payload is authoritative for (`name`, `sortName`) and
     * preserve every enrichment field. When absent, insert a minimal row with null
     * enrichment — it fills in once the contributor itself syncs.
     */
    private suspend fun contributorEnsureExists(contributor: BookContributorPayload) {
        val existing = database.contributorDao().getById(contributor.id)
        if (existing != null) {
            database.contributorDao().upsert(
                existing.copy(
                    name = contributor.name,
                    sortName = contributor.sortName,
                ),
            )
        } else {
            val now = Timestamp(currentEpochMilliseconds())
            database.contributorDao().upsert(
                ContributorEntity(
                    id = ContributorId(contributor.id),
                    name = contributor.name,
                    sortName = contributor.sortName,
                    description = null,
                    imagePath = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    /**
     * Ensure a series row exists for [series]. When present, update only `name` and
     * preserve every enrichment field. When absent, insert a minimal row with null
     * enrichment — it fills in once the series itself syncs.
     */
    private suspend fun seriesEnsureExists(series: BookSeriesPayload) {
        val existing = database.seriesDao().getById(series.id)
        if (existing != null) {
            database.seriesDao().upsert(existing.copy(name = series.name))
        } else {
            val now = Timestamp(currentEpochMilliseconds())
            database.seriesDao().upsert(
                SeriesEntity(
                    id = SeriesId(series.id),
                    name = series.name,
                    description = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }
}
