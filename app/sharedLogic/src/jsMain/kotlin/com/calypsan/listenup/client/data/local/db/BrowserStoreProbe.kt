package com.calypsan.listenup.client.data.local.db

import androidx.room3.Room
import androidx.sqlite.driver.web.WebWorkerSQLiteDriver
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.core.Timestamp
import org.w3c.dom.Worker

/**
 * What a storage self-check observed. Every field is a plain value so the result can cross
 * to a diagnostics UI or a browser test without exposing the Room layer, which is
 * `internal` to this module.
 */
data class BrowserStoreProbe(
    /** The database opened over the worker. */
    val opened: Boolean,
    /** Title read back for the probe row, or null if it was not found. */
    val roundTrippedTitle: String?,
    /** Rows returned by a trigram FTS query for a substring of the probe title. */
    val ftsMatchCount: Int,
)

/**
 * Opens the local database through [worker] and exercises it end to end: a row written and
 * read back through a real DAO, and a full-text query against the FTS5 index.
 *
 * Written as a probe rather than a health boolean because the failure modes are distinct
 * and worth telling apart — a database that opens but whose FTS index is missing looks
 * identical to a healthy one until a user searches, and ListenUp's search has no network
 * path to fall back to.
 *
 * @param worker the SQLite web worker, supplied by the application.
 * @param dbName the OPFS persistence key inside the worker's VFS.
 * @param seed when true, write the probe row first; when false, only read. Calling with
 *   `seed = false` over a fresh worker is what proves OPFS persistence rather than
 *   in-memory state.
 */
suspend fun probeBrowserStore(
    worker: Worker,
    dbName: String,
    seed: Boolean,
): BrowserStoreProbe {
    val db =
        Room
            .databaseBuilder<ListenUpDatabase>(name = dbName)
            .buildConfigured(WebWorkerSQLiteDriver(worker))

    if (seed) {
        db.bookDao().upsert(
            BookEntity(
                id = BookId(PROBE_BOOK_ID),
                libraryId = LibraryId(PROBE_LIBRARY_ID),
                folderId = FolderId(PROBE_FOLDER_ID),
                title = PROBE_TITLE,
                totalDuration = 0L,
                createdAt = Timestamp(0L),
                updatedAt = Timestamp(0L),
            ),
        )
        db.searchDao().insertBookFts(
            bookId = PROBE_BOOK_ID,
            title = PROBE_TITLE,
            subtitle = null,
            description = null,
            author = null,
            narrator = null,
            seriesName = null,
            genres = null,
        )
    }

    val readBack = db.bookDao().getById(BookId(PROBE_BOOK_ID))
    val matches = db.searchDao().searchBooks(query = PROBE_TRIGRAM_QUERY, limit = 10)

    return BrowserStoreProbe(
        opened = true,
        roundTrippedTitle = readBack?.title,
        ftsMatchCount = matches.size,
    )
}

private const val PROBE_BOOK_ID = "browser-store-probe"
private const val PROBE_LIBRARY_ID = "browser-store-probe-library"
private const val PROBE_FOLDER_ID = "browser-store-probe-folder"
private const val PROBE_TITLE = "Foundation"

/**
 * A substring, not a prefix, and deliberately so: the FTS index uses the trigram tokenizer,
 * whose whole point is that "undat" finds *Foundation*. A prefix query would pass against a
 * plain tokenizer too, and prove nothing about the configuration that actually shipped.
 */
private const val PROBE_TRIGRAM_QUERY = "undat"
