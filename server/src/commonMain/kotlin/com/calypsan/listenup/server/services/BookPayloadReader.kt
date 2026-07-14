package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookDocumentPayload
import com.calypsan.listenup.api.sync.BookGenrePayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ChapterSource
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.sqldelight.Books
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.json.Json

/** Keeps `id IN (?, ?, …)` under SQLite's variable-parameter ceiling. */
private const val SQLITE_IN_CHUNK = 900

/**
 * The stable JSON codec for the `books.field_provenance` column. A [BookField]-keyed map serializes to
 * a JSON object (`{"TITLE":{"kind":"USER","provider":null,"at":111}}`); `ignoreUnknownKeys` keeps an
 * older row readable after the enum evolves (forward-compat, matching the wire DTO). `encodeDefaults`
 * is off so an absent provider/at stays compact.
 */
private val fieldProvenanceJson = Json { ignoreUnknownKeys = true }

private val fieldProvenanceSerializer =
    MapSerializer(BookField.serializer(), FieldProvenance.serializer())

/**
 * Serializes a per-field provenance map to its `books.field_provenance` column form (a JSON object).
 * The empty map serializes to `"{}"`, matching the column default.
 */
internal fun Map<BookField, FieldProvenance>.toFieldProvenanceColumn(): String =
    fieldProvenanceJson.encodeToString(fieldProvenanceSerializer, this)

/**
 * Parses the `books.field_provenance` column back to a map. A blank/`"{}"` column is the empty map;
 * unrecognized field keys are dropped so an older row stays readable after [BookField] evolves.
 */
internal fun String.toFieldProvenance(): Map<BookField, FieldProvenance> =
    if (isBlank()) {
        emptyMap()
    } else {
        fieldProvenanceJson.decodeFromString(fieldProvenanceSerializer, this)
    }

/**
 * Builds a [BookSyncPayload] from an already-fetched root [bookRow] (a generated
 * SQLDelight [Books] row) and its child lists. Shared by per-id and batched book reads
 * so both produce byte-identical aggregates.
 *
 * The `0/1 ↔ Boolean` and `Long ↔ Int` conversions live here at the SQLDelight boundary
 * (the same place the Exposed `bool`/`integer` column adapters sat): SQLite stores
 * `abridged`/`explicit`/`has_scan_warning` as INTEGER and `publish_year` as INTEGER, which
 * SQLDelight surfaces as `Long`; the wire payload carries `Boolean`/`Int`.
 */
internal fun assembleBookPayload(
    bookRow: Books,
    contributors: List<BookContributorPayload>,
    series: List<BookSeriesPayload>,
    genres: List<BookGenrePayload>,
    audioFiles: List<BookAudioFilePayload>,
    chapters: List<BookChapterPayload>,
    documents: List<BookDocumentPayload>,
): BookSyncPayload {
    val cover =
        bookRow.cover_hash?.let { hash ->
            val coverSrc =
                bookRow.cover_source?.let { raw ->
                    CoverSource.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                }
            coverSrc?.let { CoverPayload(source = it, hash = hash) }
        }

    return BookSyncPayload(
        id = bookRow.id,
        libraryId = LibraryId(bookRow.library_id),
        folderId = FolderId(bookRow.folder_id),
        title = bookRow.title,
        sortTitle = bookRow.sort_title,
        subtitle = bookRow.subtitle,
        description = bookRow.description,
        publishYear = bookRow.publish_year?.toInt(),
        publisher = bookRow.publisher,
        language = bookRow.language,
        isbn = bookRow.isbn,
        asin = bookRow.asin,
        abridged = bookRow.abridged == 1L,
        explicit = bookRow.explicit == 1L,
        hasScanWarning = bookRow.has_scan_warning == 1L,
        totalDuration = bookRow.total_duration,
        cover = cover,
        rootRelPath = bookRow.root_rel_path,
        inode = bookRow.inode,
        scannedAt = bookRow.scanned_at,
        contributors = contributors,
        series = series,
        genres = genres,
        audioFiles = audioFiles,
        chapters = chapters,
        chapterSource =
            ChapterSource.entries.firstOrNull { it.name.equals(bookRow.chapter_source, ignoreCase = true) }
                ?: ChapterSource.EMBEDDED,
        fieldProvenance = bookRow.field_provenance.toFieldProvenance(),
        documents = documents,
        revision = bookRow.revision,
        updatedAt = bookRow.updated_at,
        createdAt = bookRow.created_at,
        deletedAt = bookRow.deleted_at,
    )
}

/**
 * Batched hydration: fetches each child table once per id-chunk via the generated
 * joined `selectByBookIds` queries, groups in memory (preserving the queried
 * ordinal/path order), and assembles payloads in input-id order, skipping ids with no
 * root row. Must be called inside an open SQLDelight transaction.
 *
 * One round-trip per child table per 900-id chunk — the N+1-safe path. Same shape as the
 * prior Exposed reader, over the generated queries.
 */
internal fun ListenUpDatabase.readBookPayloads(idStrs: List<String>): List<BookSyncPayload> {
    if (idStrs.isEmpty()) return emptyList()

    val bookRows = HashMap<String, Books>()
    val contributorsByBook = HashMap<String, MutableList<BookContributorPayload>>()
    val seriesByBook = HashMap<String, MutableList<BookSeriesPayload>>()
    val chaptersByBook = HashMap<String, MutableList<BookChapterPayload>>()
    val genresByBook = HashMap<String, MutableList<BookGenrePayload>>()
    val audioByBook = HashMap<String, MutableList<BookAudioFilePayload>>()
    val documentsByBook = HashMap<String, MutableList<BookDocumentPayload>>()

    idStrs.chunked(SQLITE_IN_CHUNK).forEach { chunk ->
        booksQueries.selectByIds(chunk).executeAsList().forEach { row -> bookRows[row.id] = row }

        bookContributorsQueries.selectByBookIds(chunk).executeAsList().forEach { row ->
            contributorsByBook
                .getOrPut(row.book_id) { mutableListOf() }
                .add(
                    BookContributorPayload(
                        id = row.contributor_id,
                        name = row.name,
                        sortName = row.sort_name,
                        role = row.role,
                        creditedAs = row.credited_as,
                    ),
                )
        }

        bookSeriesMembershipsQueries.selectByBookIds(chunk).executeAsList().forEach { row ->
            seriesByBook
                .getOrPut(row.book_id) { mutableListOf() }
                .add(
                    BookSeriesPayload(
                        id = row.series_id,
                        name = row.name,
                        sequence = row.sequence,
                    ),
                )
        }

        bookChaptersQueries.selectByBookIds(chunk).executeAsList().forEach { row ->
            chaptersByBook
                .getOrPut(row.book_id) { mutableListOf() }
                .add(
                    BookChapterPayload(
                        id = row.id,
                        title = row.title,
                        duration = row.duration,
                        startTime = row.start_time,
                    ),
                )
        }

        bookGenresQueries.selectByBookIds(chunk).executeAsList().forEach { row ->
            genresByBook
                .getOrPut(row.book_id) { mutableListOf() }
                .add(
                    BookGenrePayload(
                        id = row.id,
                        name = row.name,
                        slug = row.slug,
                        path = row.path,
                    ),
                )
        }

        bookAudioFilesQueries.selectByBookIds(chunk).executeAsList().forEach { row ->
            audioByBook
                .getOrPut(row.book_id) { mutableListOf() }
                .add(
                    BookAudioFilePayload(
                        id = row.id,
                        index = row.ordinal.toInt(),
                        filename = row.filename,
                        format = row.format,
                        codec = row.codec,
                        duration = row.duration,
                        size = row.size,
                        codecProfile = row.codecProfile,
                        spatial = row.spatial,
                        bitrate = row.bitrate?.toInt(),
                        sampleRate = row.sampleRate?.toInt(),
                        channels = row.channels?.toInt(),
                    ),
                )
        }

        bookDocumentsQueries.selectByBookIds(chunk).executeAsList().forEach { row ->
            documentsByBook
                .getOrPut(row.book_id) { mutableListOf() }
                .add(
                    BookDocumentPayload(
                        id = row.id,
                        index = row.ordinal.toInt(),
                        filename = row.filename,
                        format = row.format,
                        size = row.size,
                        hash = row.hash,
                    ),
                )
        }
    }

    return idStrs.mapNotNull { id ->
        val row = bookRows[id] ?: return@mapNotNull null
        assembleBookPayload(
            bookRow = row,
            contributors = contributorsByBook[id].orEmpty(),
            series = seriesByBook[id].orEmpty(),
            genres = genresByBook[id].orEmpty(),
            audioFiles = audioByBook[id].orEmpty(),
            chapters = chaptersByBook[id].orEmpty(),
            documents = documentsByBook[id].orEmpty(),
        )
    }
}
