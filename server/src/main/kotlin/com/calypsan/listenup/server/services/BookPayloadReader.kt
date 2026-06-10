package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookGenrePayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.BookAudioFileTable
import com.calypsan.listenup.server.db.BookChapterTable
import com.calypsan.listenup.server.db.BookContributorTable
import com.calypsan.listenup.server.db.BookGenreTable
import com.calypsan.listenup.server.db.BookSeriesMembershipTable
import com.calypsan.listenup.server.db.BookSeriesTable
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.ContributorTable
import com.calypsan.listenup.server.db.GenreTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll

/** Keeps `id IN (?, ?, …)` under SQLite's variable-parameter ceiling. */
private const val IN_LIST_CHUNK = 900

/**
 * Builds a [BookSyncPayload] from an already-fetched root [bookRow] and its child lists.
 * Shared by per-id and batched book reads so both produce byte-identical aggregates.
 */
internal fun assembleBookPayload(
    bookRow: ResultRow,
    contributors: List<BookContributorPayload>,
    series: List<BookSeriesPayload>,
    genres: List<BookGenrePayload>,
    audioFiles: List<BookAudioFilePayload>,
    chapters: List<BookChapterPayload>,
): BookSyncPayload {
    val cover =
        bookRow[BookTable.coverHash]?.let { hash ->
            val coverSrc =
                bookRow[BookTable.coverSource]?.let { raw ->
                    CoverSource.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                }
            coverSrc?.let { CoverPayload(source = it, hash = hash) }
        }

    return BookSyncPayload(
        id = bookRow[BookTable.id],
        libraryId = LibraryId(bookRow[BookTable.libraryId]),
        folderId = FolderId(bookRow[BookTable.folderId]),
        title = bookRow[BookTable.title],
        sortTitle = bookRow[BookTable.sortTitle],
        subtitle = bookRow[BookTable.subtitle],
        description = bookRow[BookTable.description],
        publishYear = bookRow[BookTable.publishYear],
        publisher = bookRow[BookTable.publisher],
        language = bookRow[BookTable.language],
        isbn = bookRow[BookTable.isbn],
        asin = bookRow[BookTable.asin],
        abridged = bookRow[BookTable.abridged],
        explicit = bookRow[BookTable.explicit],
        hasScanWarning = bookRow[BookTable.hasScanWarning],
        totalDuration = bookRow[BookTable.totalDuration],
        cover = cover,
        rootRelPath = bookRow[BookTable.rootRelPath],
        inode = bookRow[BookTable.inode],
        scannedAt = bookRow[BookTable.scannedAt],
        contributors = contributors,
        series = series,
        genres = genres,
        audioFiles = audioFiles,
        chapters = chapters,
        revision = bookRow[BookTable.revision],
        updatedAt = bookRow[BookTable.updatedAt],
        createdAt = bookRow[BookTable.createdAt],
        deletedAt = bookRow[BookTable.deletedAt],
    )
}

/**
 * Batched hydration: fetches each child table once per id-chunk via `bookId inList`,
 * groups in memory (preserving the queried ordinal/path order), and assembles payloads
 * in input-id order, skipping ids with no root row. Must be called inside an open transaction.
 */
internal fun readBookPayloads(idStrs: List<String>): List<BookSyncPayload> {
    if (idStrs.isEmpty()) return emptyList()

    val bookRows = HashMap<String, ResultRow>()
    val contributorsByBook = HashMap<String, MutableList<BookContributorPayload>>()
    val seriesByBook = HashMap<String, MutableList<BookSeriesPayload>>()
    val chaptersByBook = HashMap<String, MutableList<BookChapterPayload>>()
    val genresByBook = HashMap<String, MutableList<BookGenrePayload>>()
    val audioByBook = HashMap<String, MutableList<BookAudioFilePayload>>()

    idStrs.chunked(IN_LIST_CHUNK).forEach { chunk ->
        BookTable
            .selectAll()
            .where { BookTable.id inList chunk }
            .forEach { row -> bookRows[row[BookTable.id]] = row }

        (BookContributorTable innerJoin ContributorTable)
            .selectAll()
            .where { BookContributorTable.bookId inList chunk }
            .orderBy(BookContributorTable.ordinal)
            .forEach { row ->
                contributorsByBook.getOrPut(row[BookContributorTable.bookId]) { mutableListOf() }
                    .add(
                        BookContributorPayload(
                            id = row[ContributorTable.id],
                            name = row[ContributorTable.name],
                            sortName = row[ContributorTable.sortName],
                            role = row[BookContributorTable.role],
                            creditedAs = row[BookContributorTable.creditedAs],
                        ),
                    )
            }

        (BookSeriesMembershipTable innerJoin BookSeriesTable)
            .selectAll()
            .where { BookSeriesMembershipTable.bookId inList chunk }
            .orderBy(BookSeriesMembershipTable.ordinal)
            .forEach { row ->
                seriesByBook.getOrPut(row[BookSeriesMembershipTable.bookId]) { mutableListOf() }
                    .add(
                        BookSeriesPayload(
                            id = row[BookSeriesTable.id],
                            name = row[BookSeriesTable.name],
                            sequence = row[BookSeriesMembershipTable.sequence],
                        ),
                    )
            }

        BookChapterTable
            .selectAll()
            .where { BookChapterTable.bookId inList chunk }
            .orderBy(BookChapterTable.ordinal)
            .forEach { row ->
                chaptersByBook.getOrPut(row[BookChapterTable.bookId]) { mutableListOf() }
                    .add(
                        BookChapterPayload(
                            id = row[BookChapterTable.id],
                            title = row[BookChapterTable.title],
                            duration = row[BookChapterTable.duration],
                            startTime = row[BookChapterTable.startTime],
                        ),
                    )
            }

        (BookGenreTable innerJoin GenreTable)
            .selectAll()
            .where { (BookGenreTable.bookId inList chunk) and GenreTable.deletedAt.isNull() }
            .orderBy(GenreTable.path)
            .forEach { row ->
                genresByBook.getOrPut(row[BookGenreTable.bookId]) { mutableListOf() }
                    .add(
                        BookGenrePayload(
                            id = row[GenreTable.id],
                            name = row[GenreTable.name],
                            slug = row[GenreTable.slug],
                            path = row[GenreTable.path],
                        ),
                    )
            }

        BookAudioFileTable
            .selectAll()
            .where { BookAudioFileTable.bookId inList chunk }
            .orderBy(BookAudioFileTable.ordinal)
            .forEach { row ->
                audioByBook.getOrPut(row[BookAudioFileTable.bookId]) { mutableListOf() }
                    .add(
                        BookAudioFilePayload(
                            id = row[BookAudioFileTable.id],
                            index = row[BookAudioFileTable.ordinal],
                            filename = row[BookAudioFileTable.filename],
                            format = row[BookAudioFileTable.format],
                            codec = row[BookAudioFileTable.codec],
                            duration = row[BookAudioFileTable.duration],
                            size = row[BookAudioFileTable.size],
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
        )
    }
}
