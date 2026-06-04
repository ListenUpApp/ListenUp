package com.calypsan.listenup.server.absimport

import com.calypsan.listenup.api.dto.import.MatchTier
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.db.BookContributorTable
import com.calypsan.listenup.server.db.BookTable
import com.calypsan.listenup.server.db.ContributorTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

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
 * Every lookup is a parameterized Exposed `eq` (never string-interpolated SQL). Path and title/author
 * tiers compare normalized values: ABS `relPath` and ListenUp `rootRelPath` are not pre-normalized in
 * the database, so those tiers load `(id, key)` for the library and compare in-memory (the library is
 * bounded, so a single pass is cheap). All work happens inside one `suspendTransaction`.
 */
internal class BookMatcher(
    private val db: Database,
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
        suspendTransaction(db) {
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
        val ids =
            BookTable
                .select(BookTable.id)
                .where { (BookTable.libraryId eq libraryId.value) and (BookTable.asin eq asin) }
                .map { it[BookTable.id] }
        return resolve(ids, MatchTier.ASIN)
    }

    private fun matchByIsbn(
        item: AbsItem,
        libraryId: LibraryId,
    ): BookMatch? {
        val isbn = item.isbn ?: return null
        val ids =
            BookTable
                .select(BookTable.id)
                .where { (BookTable.libraryId eq libraryId.value) and (BookTable.isbn eq isbn) }
                .map { it[BookTable.id] }
        return resolve(ids, MatchTier.ISBN)
    }

    private fun matchByPath(
        item: AbsItem,
        libraryId: LibraryId,
    ): BookMatch? {
        val target = item.relPath?.let(::normalizeRelPath) ?: return null
        val ids =
            BookTable
                .select(BookTable.id, BookTable.rootRelPath)
                .where { BookTable.libraryId eq libraryId.value }
                .filter { normalizeRelPath(it[BookTable.rootRelPath]) == target }
                .map { it[BookTable.id] }
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
            BookTable
                .select(BookTable.id, BookTable.title)
                .where { BookTable.libraryId eq libraryId.value }
                .filter { normalizeText(it[BookTable.title]) == targetTitle }
                .map { it[BookTable.id] }
                .filter { bookId -> targetAuthor == null || authorMatches(bookId, targetAuthor) }
        return resolve(ids, MatchTier.TITLE_AUTHOR)
    }

    /**
     * True when the book has at least one `author`-role contributor whose normalized name equals
     * [targetAuthor]. When the ABS item carries an author we require it to match; this is the guard
     * that keeps two same-titled-but-different-author books from collapsing into one match.
     */
    private fun authorMatches(
        bookId: String,
        targetAuthor: String,
    ): Boolean =
        (BookContributorTable innerJoin ContributorTable)
            .select(ContributorTable.name)
            .where { (BookContributorTable.bookId eq bookId) and (BookContributorTable.role eq AUTHOR_ROLE) }
            .any { normalizeText(it[ContributorTable.name]) == targetAuthor }

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

    private companion object {
        const val AUTHOR_ROLE = "author"
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
