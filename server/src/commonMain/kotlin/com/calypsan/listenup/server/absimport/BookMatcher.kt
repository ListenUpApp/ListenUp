package com.calypsan.listenup.server.absimport

import com.calypsan.listenup.api.dto.imports.MatchTier
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction

/**
 * Matches one Audiobookshelf item against the ListenUp library using confidence tiers, biased
 * hard toward "never guess".
 *
 * Tiers are tried in descending confidence — ASIN → ISBN → PATH → TITLE_AUTHOR. The **first tier
 * that yields exactly one candidate wins**. A tier that yields more than one candidate stops the
 * search immediately and returns [MatchTier.AMBIGUOUS] with no book id — surfacing the conflict for
 * an admin rather than risking the wrong book being marked finished. Exhausting every tier with no
 * candidate returns [MatchTier.UNMATCHED].
 *
 * Every tier filters out tombstoned books (`deletedAt IS NULL`): a soft-deleted book must never be
 * auto-matched, or apply would write progress to a row the user already removed.
 *
 * Every lookup is a parameterized SQLDelight query (never string-interpolated SQL). Path and
 * title/author tiers compare normalized values: ABS `relPath` and ListenUp `rootRelPath` are not
 * pre-normalized in the database, so those tiers load `(id, key)` for the library and compare
 * in-memory (the library is bounded, so a single pass is cheap). All work happens inside one
 * `suspendTransaction`.
 */
internal class BookMatcher(
    private val sql: ListenUpDatabase,
) {
    /** The outcome of matching a single ABS item: a book id when confident, plus the tier. */
    data class BookMatch(
        val bookId: BookId?,
        val tier: MatchTier,
    )

    /** Resolves [item] to a ListenUp book in [libraryId], or surfaces ambiguity / no match. */
    suspend fun match(
        item: AbsItem,
        libraryId: LibraryId,
    ): BookMatch =
        suspendTransaction(sql) {
            matchByAsin(item, libraryId)
                ?: matchByIsbn(item, libraryId)
                ?: matchByPath(item, libraryId)
                ?: matchByTitleAuthor(item, libraryId)
                ?: BookMatch(bookId = null, tier = MatchTier.UNMATCHED)
        }

    private fun matchByAsin(
        item: AbsItem,
        libraryId: LibraryId,
    ): BookMatch? {
        val asin = item.asin ?: return null
        val ids = sql.booksQueries.selectLiveIdByLibraryAndAsin(libraryId.value, asin).executeAsList()
        return resolve(ids, MatchTier.ASIN)
    }

    private fun matchByIsbn(
        item: AbsItem,
        libraryId: LibraryId,
    ): BookMatch? {
        val isbn = item.isbn ?: return null
        val ids = sql.booksQueries.selectLiveIdByLibraryAndIsbn(libraryId.value, isbn).executeAsList()
        return resolve(ids, MatchTier.ISBN)
    }

    private fun matchByPath(
        item: AbsItem,
        libraryId: LibraryId,
    ): BookMatch? {
        val target = item.relPath?.let(::normalizeRelPath) ?: return null
        val ids =
            sql.booksQueries
                .selectLiveIdsAndPathsForLibrary(libraryId.value)
                .executeAsList()
                .filter { normalizeRelPath(it.root_rel_path) == target }
                .map { it.id }
        return resolve(ids, MatchTier.PATH)
    }

    private fun matchByTitleAuthor(
        item: AbsItem,
        libraryId: LibraryId,
    ): BookMatch? {
        val targetTitle = normalizeText(item.title)
        if (targetTitle.isEmpty()) return null
        val targetAuthor = item.authorName?.let(::normalizeText)?.takeIf { it.isNotEmpty() }
        val ids =
            sql.booksQueries
                .selectLiveIdsAndTitlesForLibrary(libraryId.value)
                .executeAsList()
                .filter { normalizeText(it.title) == targetTitle }
                .map { it.id }
                .filter { bookId -> targetAuthor == null || authorMatches(bookId, targetAuthor) }
        return resolve(ids, MatchTier.TITLE_AUTHOR)
    }

    /**
     * True when the book has at least one `author`-role contributor whose normalized name equals
     * [targetAuthor]. When the ABS item carries an author we require it to match; this is the guard
     * that keeps two same-titled-but-different-author books from collapsing into one match. The
     * `authorNamesForBooks` query already filters to the `author` role.
     */
    private fun authorMatches(
        bookId: String,
        targetAuthor: String,
    ): Boolean =
        sql.bookContributorsQueries
            .authorNamesForBooks(listOf(bookId))
            .executeAsList()
            .any { normalizeText(it.name) == targetAuthor }

    /** Exactly one → matched at [tier]; more than one → AMBIGUOUS; none → null (try the next tier). */
    private fun resolve(
        ids: List<String>,
        tier: MatchTier,
    ): BookMatch? =
        when (ids.size) {
            0 -> null
            1 -> BookMatch(bookId = BookId(ids.first()), tier = tier)
            else -> BookMatch(bookId = null, tier = MatchTier.AMBIGUOUS)
        }
}

/**
 * Normalizes a relative path for cross-source comparison: lowercases, trims, converts `\` to `/`,
 * strips a leading `/`, and collapses duplicate slashes. ABS `relPath` and ListenUp `rootRelPath`
 * can differ in case, separators, and a leading slash; normalizing both sides identically makes the
 * PATH tier robust to those cosmetic differences without fuzzy matching.
 */
internal fun normalizeRelPath(raw: String): String =
    raw
        .trim()
        .replace('\\', '/')
        .lowercase()
        .split('/')
        .filter { it.isNotEmpty() }
        .joinToString("/")

/**
 * Normalizes free text (title / author) for exact-equality comparison: lowercases, strips
 * punctuation, and collapses whitespace runs to a single space. There is deliberately no
 * edit-distance or fuzzy matching — the TITLE_AUTHOR tier requires exact normalized equality so a
 * near-miss never auto-applies.
 */
internal fun normalizeText(raw: String): String =
    raw
        .lowercase()
        .map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
        .joinToString("")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }
        .joinToString(" ")
