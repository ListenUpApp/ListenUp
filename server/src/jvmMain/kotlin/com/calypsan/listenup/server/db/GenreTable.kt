package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.sync.SyncableTable
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Server-side genres table — syncable substrate plus the hierarchy columns
 * needed for materialized-path queries (descendants, subtree-aware reparent).
 *
 * Inherits `revision`, `created_at`, `updated_at`, `deleted_at`, `client_op_id`
 * from [SyncableTable] (V23 mirrors the column set verbatim).
 *
 * Hierarchy via materialized path: [path] stores the slash-separated slug path
 * (e.g. `"/fiction/fantasy/epic-fantasy"`); [depth] is the cached component
 * count; [parentId] is the direct parent for child queries. The materialized
 * path is the authoritative shape — [parentId] is denormalised state that
 * always tracks the path. Descendant queries use the
 * `path = ? OR path LIKE ? || '/%'` collision-safe form: a parent slug of
 * `"fic"` does NOT match a sibling subtree rooted at `"fiction"`.
 */
internal object GenreTable : SyncableTable("genres") {
    val id = varchar("id", 36)
    val name = varchar("name", 200)
    val slug = varchar("slug", 100)
    val path = varchar("path", 1024)
    val parentId = reference("parent_id", id, onDelete = ReferenceOption.SET_NULL).nullable()
    val depth = integer("depth").default(0)
    val sortOrder = integer("sort_order").default(0)
    val color = varchar("color", 16).nullable()
    val description = text("description").nullable()
    override val primaryKey = PrimaryKey(id)

    /**
     * Returns the id of the live (non-tombstoned) genre with the given [slug],
     * or null when none exists. Slug uniqueness is enforced among live rows by
     * V23's `idx_genres_slug_live` partial unique index.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun findBySlug(slug: String): String? =
        selectAll()
            .where { (this@GenreTable.slug eq slug) and deletedAt.isNull() }
            .singleOrNull()
            ?.get(id)

    /**
     * Returns the id of the live genre at the given materialized [path], or
     * null when none exists. Path is unique among live genres (every node has
     * a distinct slug per parent, so paths are unique by construction).
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun findByPath(path: String): String? =
        selectAll()
            .where { (this@GenreTable.path eq path) and deletedAt.isNull() }
            .singleOrNull()
            ?.get(id)

    /**
     * Returns the ids of [pathPrefix] itself plus all of its descendants,
     * regardless of tombstone state. Uses the collision-safe pattern
     * `path = ? OR path LIKE ? || '/%'` so `"/fic"` does not match
     * `"/fiction/..."`.
     *
     * Callers that need to filter to live rows should chain a `deletedAt.isNull()`
     * predicate themselves; the method returns all matching rows because the
     * cascade operations (move, merge, delete-subtree) need to see tombstoned
     * descendants too.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun descendantIds(pathPrefix: String): List<String> =
        selectAll()
            .where { (path eq pathPrefix) or (path like "$pathPrefix/%") }
            .map { it[id] }

    /**
     * Direct children (live only) of the given parent. Returns an empty list
     * when [parentId] has no children or is itself unknown.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun directChildren(parentId: String): List<String> =
        selectAll()
            .where { (this@GenreTable.parentId eq parentId) and deletedAt.isNull() }
            .map { it[id] }

    /**
     * Bulk-rewrites the `path` and `depth` columns on the subtree rooted at
     * [oldPathPrefix], replacing the prefix with [newPathPrefix] and shifting
     * [depth] by [depthDelta]. Implements the materialized-path reparent
     * primitive (`moveGenre` / `mergeGenres` build on top).
     *
     * Raw SQL: Exposed's update DSL cannot express
     * `SET path = ? || SUBSTR(path, LENGTH(?) + 1)` as an assignment because
     * the RHS references the column being updated. Mirrors the precedent in
     * [BookContributorTable.relinkContributorPreservingCredit] for raw-SQL
     * assignments that the DSL can't reach.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun rewritePathPrefix(
        oldPathPrefix: String,
        newPathPrefix: String,
        depthDelta: Int,
    ) {
        TransactionManager.current().exec(
            stmt =
                "UPDATE genres " +
                    "SET path = ? || SUBSTR(path, LENGTH(?) + 1), depth = depth + ? " +
                    "WHERE path = ? OR path LIKE ? || '/%'",
            args =
                listOf(
                    TextColumnType() to newPathPrefix,
                    TextColumnType() to oldPathPrefix,
                    IntegerColumnType() to depthDelta,
                    TextColumnType() to oldPathPrefix,
                    TextColumnType() to oldPathPrefix,
                ),
        )
    }

    /**
     * Updates a single genre's `parent_id` to [newParentId] (null clears the
     * pointer, marking the row as a root). Used by `moveGenre` after
     * [rewritePathPrefix] has rewritten the subtree's materialized paths.
     *
     * Exposed v1's typed DSL handles nullable FK assignment via
     * `it[parentId] = newParentId` — no raw SQL needed, no JDBC NULL-binding
     * gotcha.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun updateParentId(
        id: String,
        newParentId: String?,
    ) {
        update({ GenreTable.id eq id }) {
            it[parentId] = newParentId
        }
    }
}
