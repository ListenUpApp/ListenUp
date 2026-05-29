package com.calypsan.listenup.server.api

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.server.sync.SqlFragment
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction

/**
 * The single source of truth for **book-level** visibility: the set of book ids a
 * `(userId, role)` may see. Every seam that scopes books to a viewer — `BookService`,
 * `SearchService`, the sync firehose — derives from this one definition rather than
 * re-deriving the rule, so the access boundary can never drift between surfaces.
 *
 * The rule, in precedence-free set terms — a book is visible to a non-admin member when
 * it is live (`deleted_at IS NULL`) **and** either:
 *  - it is **uncollected** — in no live collection, so public by default; or
 *  - it is in **at least one** live collection the member can reach: one they own, one
 *    flagged [global-access][com.calypsan.listenup.server.db.CollectionsTable.isGlobalAccess]
 *    (visible to all authenticated members), or one shared with them via a live
 *    [share][com.calypsan.listenup.server.db.CollectionSharesTable].
 *
 * A book in a private collection with no reachable relationship is denied; a book in both
 * a private and a reachable collection is allowed (≥1 reachable wins). ROOT and ADMIN
 * bypass the filter entirely — every live book, including inbox and private ones.
 *
 * Sibling to [CollectionAccessPolicy], which owns the collection-level decision; this owns
 * the book-level one. The single SQL definition lives in [accessibleBookIdsSql]; both
 * [accessibleBookIds] and [canAccess] are thin derivations of it.
 */
internal class BookAccessPolicy(private val db: Database) {
    /**
     * The WHERE-ready subquery selecting the ids of every book visible to `(userId, role)`,
     * with its positional args — or `null` for ROOT/ADMIN, who see all live books (an
     * unconstrained filter). The single owned definition; [accessibleBookIds] and
     * [canAccess] both build on it.
     *
     * The returned [SqlFragment.sql] is a complete `SELECT b2.id FROM books b2 …` subquery,
     * so callers can wrap it (`SELECT 1 FROM ($sql) acc WHERE acc.id = ?`) or run it directly.
     */
    fun accessibleBookIdsSql(
        userId: String,
        role: UserRole,
    ): SqlFragment? {
        if (role == UserRole.ROOT || role == UserRole.ADMIN) return null
        val sql =
            """
            SELECT b2.id FROM books b2
            WHERE b2.deleted_at IS NULL AND (
              NOT EXISTS (
                SELECT 1 FROM collection_books cb
                JOIN collections c ON c.id = cb.collection_id AND c.deleted_at IS NULL
                WHERE cb.book_id = b2.id AND cb.deleted_at IS NULL
              )
              OR EXISTS (
                SELECT 1 FROM collection_books cb
                JOIN collections c ON c.id = cb.collection_id AND c.deleted_at IS NULL
                WHERE cb.book_id = b2.id AND cb.deleted_at IS NULL AND (
                  c.owner_id = ?
                  OR c.is_global_access = 1
                  OR EXISTS (
                    SELECT 1 FROM collection_shares s
                    WHERE s.collection_id = c.id AND s.shared_with_user_id = ? AND s.deleted_at IS NULL
                  )
                )
              )
            )
            """.trimIndent()
        return SqlFragment(
            sql = sql,
            args = listOf(TextColumnType() to userId, TextColumnType() to userId),
        )
    }

    /**
     * Materialises [accessibleBookIdsSql] into the set of visible book ids.
     *
     * Returns `null` for ROOT/ADMIN — the unconstrained "all live books" case — so callers
     * can distinguish "no filter" from "an empty visible set". Provided for seams that need
     * the id set in memory (e.g. firehose scoping); [canAccess] is the cheaper single-book path.
     */
    suspend fun accessibleBookIds(
        userId: String,
        role: UserRole,
    ): Set<String>? {
        val frag = accessibleBookIdsSql(userId, role) ?: return null
        return suspendTransaction(db) {
            val ids = mutableSetOf<String>()
            TransactionManager.current().exec(stmt = frag.sql, args = frag.args) { rs ->
                while (rs.next()) ids += rs.getString(1)
            }
            ids
        }
    }

    /**
     * True when `(userId, role)` may see [bookId]. ROOT/ADMIN see any live book; everyone
     * else is gated by [accessibleBookIdsSql], probed for this one id so no full id set is
     * materialised.
     */
    suspend fun canAccess(
        userId: String,
        role: UserRole,
        bookId: String,
    ): Boolean =
        suspendTransaction(db) {
            val frag =
                accessibleBookIdsSql(userId, role)
                    ?: return@suspendTransaction bookExists(bookId)
            var visible = false
            TransactionManager.current().exec(
                stmt = "SELECT 1 FROM (${frag.sql}) acc WHERE acc.id = ? LIMIT 1",
                args = frag.args + listOf<Pair<IColumnType<*>, Any>>(TextColumnType() to bookId),
            ) { rs -> visible = rs.next() }
            visible
        }

    /** True when a live (non-tombstoned) book row with [bookId] exists — the ROOT/ADMIN check. */
    private fun bookExists(bookId: String): Boolean {
        var exists = false
        TransactionManager.current().exec(
            stmt = "SELECT 1 FROM books WHERE id = ? AND deleted_at IS NULL LIMIT 1",
            args = listOf<Pair<IColumnType<*>, Any>>(TextColumnType() to bookId),
        ) { rs -> exists = rs.next() }
        return exists
    }
}
