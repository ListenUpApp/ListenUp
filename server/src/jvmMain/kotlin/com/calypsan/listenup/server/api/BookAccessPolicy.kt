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
 *    (visible to all authenticated members), or one granted to them via a live
 *    [grant][com.calypsan.listenup.server.db.CollectionGrantsTable].
 *
 * A book in a private collection with no reachable relationship is denied; a book in both
 * a private and a reachable collection is allowed (≥1 reachable wins). ROOT and ADMIN
 * bypass the filter entirely — every live book, including inbox and private ones.
 *
 * **System collections and book-level visibility:** `ALL_BOOKS` and `INBOX` are
 * server-managed substrate collections. They are excluded from the COLLECTION-domain sync
 * fragments ([accessibleCollectionIdsSql], [accessibleCollectionBookIdsSql],
 * [visibleCollectionGrantIdsSql]) via an explicit `type NOT IN ('ALL_BOOKS','INBOX')`
 * clause, so members never receive these rows in their collection sync. Book-level
 * visibility ([accessibleBookIdsSql]) is deliberately **unchanged**: a book stored in
 * `ALL_BOOKS` remains visible to a member via the grant branch of that fragment — the
 * system collection provides visibility at the book level even though the collection row
 * itself never appears in a member's collection sync.
 *
 * Sibling to [CollectionAccessPolicy], which owns the collection-level *mutation* decision
 * (a repository-backed [CollectionAccessPolicy.Decision] used to gate writes); this owns the
 * raw-SQL *visibility* definitions consumed by the sync seams. Both the book-visibility rule
 * ([accessibleBookIdsSql]) and its collection-id analogs ([accessibleCollectionIdsSql],
 * [visibleCollectionGrantIdsSql], [accessibleCollectionBookIdsSql]) live here because they
 * share the identical `owner / global-access / active-grant` predicate, the same [Database]
 * handle, and the same [SqlFragment] splicing contract — keeping them together means the
 * access boundary is one definition, spliced into every sync domain that scopes to a viewer.
 */
class BookAccessPolicy(
    private val db: Database,
) {
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
                    SELECT 1 FROM collection_grants g
                    WHERE g.collection_id = c.id AND g.principal_type = 'USER'
                      AND g.principal_id = ? AND g.deleted_at IS NULL
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

    /**
     * The WHERE-ready subquery selecting the ids of every live collection visible to
     * `(userId, role)` — the collection-id analog of [accessibleBookIdsSql] — or `null` for
     * ROOT/ADMIN, who see every collection (including system ones and private ones).
     *
     * A non-admin member sees a live collection (`deleted_at IS NULL`) they own, one flagged
     * `is_global_access`, or one shared with them via a live share, **excluding** system
     * collections (`ALL_BOOKS`, `INBOX`) which are filtered by an explicit
     * `type NOT IN ('ALL_BOOKS','INBOX')` clause. System collections are server-managed
     * substrate and must never appear in a member's COLLECTION-domain sync — they are
     * excluded here rather than relying on ownership or sharing: `ALL_BOOKS` in particular
     * is reachable by every member via a default grant and would appear without this clause.
     *
     * The returned [SqlFragment.sql] is a complete `SELECT c.id FROM collections c …` subquery,
     * spliced by the sync substrate as `collections.id IN (<sql>)`.
     */
    fun accessibleCollectionIdsSql(
        userId: String,
        role: UserRole,
    ): SqlFragment? {
        if (role == UserRole.ROOT || role == UserRole.ADMIN) return null
        return SqlFragment(
            sql = accessibleCollectionIdsSubquery,
            args =
                listOf(
                    TextColumnType() to userId,
                    TextColumnType() to userId,
                ),
        )
    }

    /**
     * The WHERE-ready subquery selecting the ids of every `collection_grants` row visible to
     * `(userId, role)` — or `null` for ROOT/ADMIN, who see every grant. A non-admin sees a
     * USER grant that names them (`principal_id = ?`), **excluding grants on system collections**
     * (`ALL_BOOKS`, `INBOX`), or a grant on a live collection they own.
     *
     * The system-collection exclusion on the `principal_id = ?` branch is required because every
     * member holds a default `ALL_BOOKS` grant issued at registration. Without the exclusion that
     * grant row would appear in the member's `collection_shares` sync, leaking a system-collection
     * id to the client. Grants on collections the member *owns* are never system collections
     * (members cannot own system collections), so the owner branch needs no exclusion.
     *
     * Distinct from [accessibleCollectionIdsSql]: a member sees the grants granting *them*
     * access even when the collection itself reaches them only via that grant, and sees every
     * grant on collections they own (so the owner's client can reconcile its grant list).
     *
     * The wire domain is still `collection_shares` (a USER grant is a share on the wire), so the
     * sync substrate splices this as `collection_shares.id IN (<sql>)` against the renamed table.
     */
    fun visibleCollectionGrantIdsSql(
        userId: String,
        role: UserRole,
    ): SqlFragment? {
        if (role == UserRole.ROOT || role == UserRole.ADMIN) return null
        val sql =
            """
            SELECT g.id FROM collection_grants g
            WHERE (
                g.principal_type = 'USER' AND g.principal_id = ?
                AND g.collection_id NOT IN (SELECT id FROM collections WHERE type IN ($systemTypeList) AND deleted_at IS NULL)
              )
              OR g.collection_id IN (
                SELECT c.id FROM collections c WHERE c.deleted_at IS NULL AND c.owner_id = ?
              )
            """.trimIndent()
        return SqlFragment(sql = sql, args = listOf(TextColumnType() to userId, TextColumnType() to userId))
    }

    /**
     * The WHERE-ready subquery selecting the synthetic ids (`"$collectionId:$bookId"`) of every
     * `collection_books` junction row whose collection is visible to `(userId, role)` — or `null`
     * for ROOT/ADMIN, who see every membership.
     *
     * The selected `cb.id` matches the synthetic key the junction's [SyncableRepository] stores,
     * so the substrate's `collection_books.id IN (<sql>)` splice lines up exactly.
     */
    fun accessibleCollectionBookIdsSql(
        userId: String,
        role: UserRole,
    ): SqlFragment? {
        if (role == UserRole.ROOT || role == UserRole.ADMIN) return null
        val sql =
            """
            SELECT cb.id FROM collection_books cb
            WHERE cb.collection_id IN ($accessibleCollectionIdsSubquery)
            """.trimIndent()
        return SqlFragment(sql = sql, args = listOf(TextColumnType() to userId, TextColumnType() to userId))
    }

    /**
     * True when `(userId, role)` may see collection [collectionId]. ROOT/ADMIN see any live
     * collection; everyone else is gated by [accessibleCollectionIdsSql], probed for this one
     * id. The single-collection probe behind the firehose gate for collection-domain events.
     */
    suspend fun canAccessCollection(
        userId: String,
        role: UserRole,
        collectionId: String,
    ): Boolean =
        suspendTransaction(db) {
            val frag =
                accessibleCollectionIdsSql(userId, role)
                    ?: return@suspendTransaction collectionExists(collectionId)
            var visible = false
            TransactionManager.current().exec(
                stmt = "SELECT 1 FROM (${frag.sql}) acc WHERE acc.id = ? LIMIT 1",
                args = frag.args + listOf<Pair<IColumnType<*>, Any>>(TextColumnType() to collectionId),
            ) { rs -> visible = rs.next() }
            visible
        }

    /**
     * True when [userId] owns the live collection [collectionId]. The owner-only branch behind
     * the `collection_shares` firehose gate — a member sees grant events on collections they own
     * (matching [visibleCollectionGrantIdsSql]), independent of global-access or grant reach.
     */
    suspend fun ownsCollection(
        userId: String,
        collectionId: String,
    ): Boolean =
        suspendTransaction(db) {
            var owns = false
            TransactionManager.current().exec(
                stmt = "SELECT 1 FROM collections WHERE id = ? AND owner_id = ? AND deleted_at IS NULL LIMIT 1",
                args = listOf<Pair<IColumnType<*>, Any>>(TextColumnType() to collectionId, TextColumnType() to userId),
            ) { rs -> owns = rs.next() }
            owns
        }

    /** True when a live (non-tombstoned) collection row with [collectionId] exists — the ROOT/ADMIN check. */
    private fun collectionExists(collectionId: String): Boolean {
        var exists = false
        TransactionManager.current().exec(
            stmt = "SELECT 1 FROM collections WHERE id = ? AND deleted_at IS NULL LIMIT 1",
            args = listOf<Pair<IColumnType<*>, Any>>(TextColumnType() to collectionId),
        ) { rs -> exists = rs.next() }
        return exists
    }

    // system-collection type discriminators, single-sourced from SystemCollectionType to avoid SQL/enum drift
    private val systemTypeList = "'$SYSTEM_TYPE_ALL_BOOKS','$SYSTEM_TYPE_INBOX'"

    /**
     * The shared `(owner OR global-access OR active-grant)` collection-id subquery with an
     * explicit `type NOT IN ('ALL_BOOKS','INBOX')` guard, bound to two positional `?` placeholders
     * (both the user id: owner check, then grant check). Reused by [accessibleCollectionIdsSql]
     * and embedded in [accessibleCollectionBookIdsSql] — both inherit the system-collection
     * exclusion, as does [canAccessCollection] which probes this subquery directly.
     */
    private val accessibleCollectionIdsSubquery: String =
        """
        SELECT c.id FROM collections c
        WHERE c.deleted_at IS NULL AND c.type NOT IN ($systemTypeList) AND (
          c.owner_id = ?
          OR c.is_global_access = 1
          OR EXISTS (
            SELECT 1 FROM collection_grants g
            WHERE g.collection_id = c.id AND g.principal_type = 'USER'
              AND g.principal_id = ? AND g.deleted_at IS NULL
          )
        )
        """.trimIndent()
}
