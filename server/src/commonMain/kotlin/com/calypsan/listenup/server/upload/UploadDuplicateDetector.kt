package com.calypsan.listenup.server.upload

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.absimport.normalizeText
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.sidecar.chapterFingerprintOf

/**
 * Answers one question about a staged upload: **is this book already in the library?**
 *
 * The tiers are the identity fallback chain the sidecar already writes into every
 * `listenup.json` — ASIN, then the edition-tolerant chapter fingerprint, then title/author —
 * used here for the same reason it exists there: those three signals are what survives a
 * re-encode, a re-download from a different store, and a rename.
 *
 * It differs from [com.calypsan.listenup.server.absimport.BookMatcher] in what it does with more
 * than one hit. The importer treats ambiguity as a reason to stop and ask, because it is about to
 * write *progress* onto a book and the wrong one is a silent lie. Here the question is only
 * "does the library already hold this", and two candidate matches answer it just as firmly as one
 * — so the first hit at the highest tier wins and the upload is refused. Refusing costs the admin
 * one message naming the book they already have; the alternative costs them a duplicate library.
 */
internal class UploadDuplicateDetector(
    private val sql: ListenUpDatabase,
) {
    /** An existing library book an upload matched. [title] is what the refusal message names. */
    data class ExistingBook(
        val bookId: String,
        val title: String,
    )

    /** The book in [libraryId] that [analyzed] duplicates, or null when it is genuinely new. */
    suspend fun findExisting(
        libraryId: LibraryId,
        analyzed: AnalyzedBook,
    ): ExistingBook? =
        suspendTransaction(sql) {
            val id =
                matchByAsin(libraryId, analyzed)
                    ?: matchByChapterFingerprint(libraryId, analyzed)
                    ?: matchByTitleAuthor(libraryId, analyzed)
            id?.let { ExistingBook(bookId = it, title = titleOf(it)) }
        }

    private fun matchByAsin(
        libraryId: LibraryId,
        analyzed: AnalyzedBook,
    ): String? {
        val asin = analyzed.asin?.takeIf { it.isNotBlank() } ?: return null
        return sql.booksQueries
            .selectLiveIdByLibraryAndAsin(libraryId.value, asin)
            .executeAsList()
            .firstOrNull()
    }

    /**
     * The middle tier: two files that carry the same chapter titles and near-identical chapter
     * lengths are the same book even when neither carries an ASIN and the titles were typed
     * differently. Durations bucket to 5s inside [chapterFingerprintOf], so a re-encode that
     * shifts boundaries by a few hundred milliseconds still matches.
     *
     * Recomputes each live book's fingerprint from one flat `(book_id, title, duration)` read
     * rather than storing it: the value is derived, and a stored copy is one more thing that can
     * be stale against the chapters it claims to describe.
     */
    private fun matchByChapterFingerprint(
        libraryId: LibraryId,
        analyzed: AnalyzedBook,
    ): String? {
        val target =
            chapterFingerprintOf(analyzed.chapters.map { it.title to it.endMs - it.startMs })
                ?: return null
        val byBook = LinkedHashMap<String, MutableList<Pair<String, Long>>>()
        sql.bookChaptersQueries.selectChapterIdentityForLibrary(libraryId.value).executeAsList().forEach { row ->
            byBook.getOrPut(row.book_id) { mutableListOf() } += row.title to row.duration
        }
        return byBook.entries.firstOrNull { (_, chapters) -> chapterFingerprintOf(chapters) == target }?.key
    }

    /**
     * The last-resort tier: exact equality of *normalized* title, and — when the upload carries an
     * author — of a normalized author name too. Normalization is
     * [com.calypsan.listenup.server.absimport.normalizeText], the same one the ABS importer uses,
     * so "The Way of Kings" and "the way of kings!" are one book and nothing fuzzier is.
     *
     * The author check is the guard that keeps two unrelated books with a common title (every
     * library has a couple) from collapsing into a false duplicate.
     */
    private fun matchByTitleAuthor(
        libraryId: LibraryId,
        analyzed: AnalyzedBook,
    ): String? {
        val targetTitle = normalizeText(analyzed.title)
        if (targetTitle.isEmpty()) return null
        val targetAuthor =
            analyzed.authors
                .firstOrNull()
                ?.let(::normalizeText)
                ?.takeIf { it.isNotEmpty() }
        return sql.booksQueries
            .selectLiveIdsAndTitlesForLibrary(libraryId.value)
            .executeAsList()
            .filter { normalizeText(it.title) == targetTitle }
            .map { it.id }
            .firstOrNull { bookId -> targetAuthor == null || authorMatches(bookId, targetAuthor) }
    }

    private fun authorMatches(
        bookId: String,
        targetAuthor: String,
    ): Boolean =
        sql.bookContributorsQueries
            .authorNamesForBooks(listOf(bookId))
            .executeAsList()
            .any { normalizeText(it.name) == targetAuthor }

    private fun titleOf(bookId: String): String =
        sql.booksQueries
            .selectById(bookId)
            .executeAsOneOrNull()
            ?.title
            .orEmpty()
}
