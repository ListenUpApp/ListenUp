package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookDocumentPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ChapterSource
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import kotlin.uuid.Uuid

/**
 * Transaction-scoped helpers for writing the book root row, identity columns, and child tables
 * over the generated SQLDelight queries.
 *
 * All methods issue queries against the already-open SQLDelight transaction; they do NOT bump the
 * global revision, publish to the change bus, or open their own transaction — those are the
 * substrate's responsibility. [BookRepository.writePayload] provides the open transaction context.
 */
internal class BookAggregateWriter(
    private val db: ListenUpDatabase,
    private val bookGenreWriter: BookGenreWriter,
    private val coverWriter: BookCoverWriter,
    private val writeSystemMembership: (systemCollectionId: String, bookId: String, now: Long) -> Unit,
) {
    /**
     * Applies the identity invariants ([withReconciledIdentity]) and writes the full book
     * aggregate inside the caller's open SQLDelight transaction.
     */
    fun writePayload(
        value: BookSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        existed: Boolean,
    ) {
        val reconciled = value.withReconciledIdentity()
        writeReconciledPayload(value = reconciled, rev = rev, now = now, clientOpId = clientOpId, existed = existed)
        writeIdentityColumns(reconciled)
    }

    /**
     * Writes the full book aggregate inside the substrate's open SQLDelight transaction.
     *
     * **Atomicity is the contract.** The root row, the child tables (contributors, series, chapters,
     * audio files, documents) and — for a genuinely-new book the scan path assigned to a system
     * collection — the book→collection `collection_books` membership ([writeSystemMembership]) land
     * together or not at all.
     *
     * Scan writes land their genre junctions here too, inside this transaction, from the ids pre-resolved
     * into [BookWriteExtras.genreIds]. A null `genreIds` leaves the stored set alone.
     */
    private fun writeReconciledPayload(
        value: BookSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        existed: Boolean,
    ) {
        // Read per-call extras the scan/edit paths installed via the coroutine context (carried by
        // a TransactionLocal and mirrored onto this thread by suspendTransaction).
        val extras = BookWriteExtras.current()
        val managedCover = extras?.managedCover

        val fieldProvenanceColumn = value.fieldProvenance.toFieldProvenanceColumn()
        val preserveContributors = extras?.preserveContributors == true
        val preserveSeries = extras?.preserveSeries == true

        // Sticky-user-chapters merge: if the existing row carries a user-edited chapter set
        // (chapter_source = 'user'), preserve the chapter rows so a re-scan does not clobber
        // an intentional user edit. A subsequent USER edit (value.chapterSource == USER) is
        // still allowed through.
        val existingChapterSource =
            if (existed) {
                db.booksQueries
                    .selectChapterSourceById(value.id)
                    .executeAsOneOrNull()
            } else {
                null
            }
        val preserveChapters =
            existingChapterSource == ChapterSource.USER.name.lowercase() &&
                value.chapterSource != ChapterSource.USER

        if (existed) {
            // Sticky-upload merge: if the existing row carries a user-uploaded cover
            // (cover_source = 'uploaded'), preserve the cover columns so a re-scan does not
            // clobber an intentional user choice.
            val existingCover =
                db.booksQueries
                    .selectCoverColumnsById(value.id)
                    .executeAsOneOrNull()
                    ?.let { ExistingCoverColumns(it.cover_source, it.cover_path, it.cover_hash) }
            val isUploadedLocked = existingCover?.source == CoverSource.UPLOADED.name.lowercase()

            if (isUploadedLocked) {
                updateContentKeepingCover(
                    value = value,
                    rev = rev,
                    now = now,
                    clientOpId = clientOpId,
                    fieldProvenanceColumn = fieldProvenanceColumn,
                )
            } else {
                val cover = coverWriter.resolveCoverColumns(value, managedCover, existingCover)
                db.booksQueries.updateContent(
                    title = value.title,
                    sort_title = value.sortTitle,
                    subtitle = value.subtitle,
                    description = value.description,
                    publish_year = value.publishYear?.toLong(),
                    normalization_gain_db = value.normalizationGainDb?.toDouble(),
                    publisher = value.publisher,
                    language = value.language,
                    isbn = value.isbn,
                    asin = value.asin,
                    abridged = value.abridged.toDbLong(),
                    explicit = value.explicit.toDbLong(),
                    has_scan_warning = value.hasScanWarning.toDbLong(),
                    total_duration = value.totalDuration,
                    cover_source = cover.source,
                    cover_path = cover.path,
                    cover_hash = cover.hash,
                    field_provenance = fieldProvenanceColumn,
                    root_rel_path = value.rootRelPath,
                    inode = value.inode,
                    scanned_at = value.scannedAt,
                    revision = rev,
                    updated_at = now,
                    client_op_id = clientOpId,
                    id = value.id,
                )
            }
            // Edit-path only: re-stamp the added date. `updateContent`/`updateContentPreserveCover`
            // never write created_at, so a rescan's placeholder value stays ignored.
            extras?.createdAtOverride?.let { db.booksQueries.updateCreatedAt(it, value.id) }
        } else {
            val cover = coverWriter.resolveCoverColumns(value, managedCover, existing = null)
            db.booksQueries.insert(
                id = value.id,
                library_id = value.libraryId.value,
                folder_id = value.folderId.value,
                title = value.title,
                sort_title = value.sortTitle,
                subtitle = value.subtitle,
                description = value.description,
                publish_year = value.publishYear?.toLong(),
                normalization_gain_db = value.normalizationGainDb?.toDouble(),
                publisher = value.publisher,
                language = value.language,
                isbn = value.isbn,
                asin = value.asin,
                abridged = value.abridged.toDbLong(),
                explicit = value.explicit.toDbLong(),
                has_scan_warning = value.hasScanWarning.toDbLong(),
                total_duration = value.totalDuration,
                cover_source = cover.source,
                cover_path = cover.path,
                cover_hash = cover.hash,
                field_provenance = fieldProvenanceColumn,
                root_rel_path = value.rootRelPath,
                inode = value.inode,
                scanned_at = value.scannedAt,
                book_tier_label = value.bookTierLabel,
                part_tier_label = value.partTierLabel,
                revision = rev,
                created_at = now,
                updated_at = now,
                deleted_at = null,
                client_op_id = clientOpId,
            )
            // Atomic system-collection membership on genuine insert only.
            extras?.systemCollectionId?.let { sysId -> writeSystemMembership(sysId, value.id, now) }
        }

        replaceBookChildren(
            value = value,
            preserveContributors = preserveContributors,
            preserveSeries = preserveSeries,
            preserveChapters = preserveChapters,
        )

        // Scan paths only: genre junctions ride INSIDE this transaction from the pre-resolved ids.
        extras?.genreIds?.let { genreIds -> bookGenreWriter.writeJunctions(value.id, genreIds) }
    }

    /** The rescan UPDATE for a row whose user-uploaded cover is sticky: every content column except the cover. */
    private fun updateContentKeepingCover(
        value: BookSyncPayload,
        rev: Long,
        now: Long,
        clientOpId: String?,
        fieldProvenanceColumn: String,
    ) {
        db.booksQueries.updateContentPreserveCover(
            title = value.title,
            sort_title = value.sortTitle,
            subtitle = value.subtitle,
            description = value.description,
            publish_year = value.publishYear?.toLong(),
            normalization_gain_db = value.normalizationGainDb?.toDouble(),
            publisher = value.publisher,
            language = value.language,
            isbn = value.isbn,
            asin = value.asin,
            abridged = value.abridged.toDbLong(),
            explicit = value.explicit.toDbLong(),
            has_scan_warning = value.hasScanWarning.toDbLong(),
            total_duration = value.totalDuration,
            field_provenance = fieldProvenanceColumn,
            root_rel_path = value.rootRelPath,
            inode = value.inode,
            scanned_at = value.scannedAt,
            revision = rev,
            updated_at = now,
            client_op_id = clientOpId,
            id = value.id,
        )
    }

    /**
     * The release date and the catalogue refs, riding the caller's transaction and revision. Both are
     * written by targeted statements so insert/updateContent (and their many callers) stay as they were.
     */
    private fun writeIdentityColumns(value: BookSyncPayload) {
        db.booksQueries.updateReleaseDate(release_date = value.releaseDate, id = value.id)
        db.replaceExternalRefs(ExternalRefKind.BOOK, value.id, value.externalRefs)
    }

    /**
     * Replaces a book's child-collection rows after the parent upsert, honouring the per-field
     * provenance preserve flags: a contributors/series/chapters edit the stored book carries (and this
     * write isn't itself re-editing) keeps its rows — the collection analogue of the scalar preserve in
     * [mergeByProvenance]. Audio files and documents always refresh.
     */
    private fun replaceBookChildren(
        value: BookSyncPayload,
        preserveContributors: Boolean,
        preserveSeries: Boolean,
        preserveChapters: Boolean,
    ) {
        if (!preserveContributors) replaceContributors(value.id, value.contributors)
        if (!preserveSeries) replaceSeries(value.id, value.series)
        if (!preserveChapters) {
            replaceChapters(value.id, value.chapters)
            db.booksQueries.updateChapterSource(value.chapterSource.name.lowercase(), value.id)
        }
        replaceAudioFiles(value.id, value.audioFiles)
        replaceDocuments(value.id, value.documents)
    }

    /**
     * Replaces this book's contributor junction rows. The caller MUST supply a
     * payload whose contributor ids already exist in `contributors` —
     * `upsertFromAnalyzed` satisfies this by resolving every contributor through
     * [ContributorRepository.resolveOrCreate] before calling `upsert`.
     */
    fun replaceContributors(
        bookId: String,
        contributors: List<BookContributorPayload>,
    ) {
        db.bookContributorsQueries.deleteByBookId(bookId)
        // Two display names can resolve to the same contributor id when sortName deduplication
        // collapses them. Collapse to one row per (contributor, role) — first occurrence wins.
        val deduped = contributors.distinctBy { it.id to it.role }
        deduped.forEachIndexed { idx, c ->
            db.bookContributorsQueries.insert(
                book_id = bookId,
                contributor_id = c.id,
                role = c.role,
                credited_as = c.creditedAs,
                ordinal = idx.toLong(),
            )
        }
    }

    /**
     * Replaces this book's series junction rows. The caller MUST supply a
     * payload whose series ids already exist in `book_series` —
     * `upsertFromAnalyzed` satisfies this by resolving every series through
     * [SeriesRepository.resolveOrCreate] before calling `upsert`.
     */
    fun replaceSeries(
        bookId: String,
        series: List<BookSeriesPayload>,
    ) {
        db.bookSeriesMembershipsQueries.deleteByBookId(bookId)
        val deduped = series.distinctBy { it.id }
        deduped.forEachIndexed { idx, s ->
            db.bookSeriesMembershipsQueries.insert(
                book_id = bookId,
                series_id = s.id,
                sequence = s.sequence,
                ordinal = idx.toLong(),
            )
        }
    }

    fun replaceChapters(
        bookId: String,
        chapters: List<BookChapterPayload>,
    ) {
        db.bookChaptersQueries.deleteByBookId(bookId)
        chapters.forEachIndexed { idx, ch ->
            db.bookChaptersQueries.insert(
                book_id = bookId,
                ordinal = idx.toLong(),
                id = ch.id.ifBlank { Uuid.random().toString() },
                title = ch.title,
                duration = ch.duration,
                start_time = ch.startTime,
                part_title = ch.partTitle,
                book_title = ch.bookTitle,
            )
        }
    }

    fun replaceAudioFiles(
        bookId: String,
        files: List<BookAudioFilePayload>,
    ) {
        db.bookAudioFilesQueries.deleteByBookId(bookId)
        files.forEachIndexed { idx, f ->
            db.bookAudioFilesQueries.insert(
                book_id = bookId,
                ordinal = idx.toLong(),
                id = f.id.ifBlank { Uuid.random().toString() },
                filename = f.filename,
                format = f.format,
                codec = f.codec,
                duration = f.duration,
                size = f.size,
                codecProfile = f.codecProfile,
                spatial = f.spatial,
                bitrate = f.bitrate?.toLong(),
                sampleRate = f.sampleRate?.toLong(),
                channels = f.channels?.toLong(),
            )
        }
    }

    fun replaceDocuments(
        bookId: String,
        documents: List<BookDocumentPayload>,
    ) {
        db.bookDocumentsQueries.deleteByBookId(bookId)
        documents.forEachIndexed { idx, d ->
            db.bookDocumentsQueries.insert(
                book_id = bookId,
                ordinal = idx.toLong(),
                id = d.id.ifBlank { Uuid.random().toString() },
                filename = d.filename,
                format = d.format,
                size = d.size,
                hash = d.hash,
            )
        }
    }
}
