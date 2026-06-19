package com.calypsan.listenup.server.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/**
 * Junction table between books and contributors, with role.
 *
 * Composite PK `(book_id, contributor_id, role)` so a single person credited as
 * both author and narrator on the same book yields two rows. `ordinal` preserves
 * the on-wire order across upserts.
 */
internal object BookContributorTable : Table("book_contributors") {
    val bookId = reference("book_id", BookTable.id, onDelete = ReferenceOption.CASCADE)
    val contributorId = reference("contributor_id", ContributorTable.id)
    val role = varchar("role", 64)
    val creditedAs = varchar("credited_as", 512).nullable()
    val ordinal = integer("ordinal")
    override val primaryKey = PrimaryKey(bookId, contributorId, role)

    init {
        index("idx_bc_contributor_role", false, contributorId, role)
    }

    /**
     * Distinct book IDs currently linked (via any role) to the contributor
     * identified by [id]. Used by Books-C1's deleteContributor cascade and
     * the updateContributor reindex flow.
     *
     * Must be called inside a `suspendTransaction { ... }` block.
     */
    fun bookIdsForContributor(id: String): List<String> =
        selectAll()
            .where { contributorId eq id }
            .map { it[bookId] }
            .distinct()

    /**
     * Hard-deletes every junction row referencing [id]. Used by Books-C1's
     * deleteContributor cascade. Returns the number of rows removed.
     *
     * Must be called inside a `suspendTransaction { ... }` block.
     */
    fun deleteAllForContributor(id: String): Int = deleteWhere { contributorId eq id }

    /**
     * Re-links all `book_contributors` rows from [fromId] to [toId], capturing
     * the source contributor's display name into the `credited_as` column when
     * that column was previously NULL.
     *
     * This preserves per-book credit history after a contributor merge — books
     * originally credited under [sourceName] (e.g. "Robert Galbraith") continue
     * to display that credit even though they now point at the target contributor
     * (e.g. J.K. Rowling). Books that already had an explicit `credited_as` value
     * keep it untouched; `COALESCE` only fills the NULL slots.
     *
     * Must be called inside a `suspendTransaction { ... }` block.
     */
    fun relinkContributorPreservingCredit(
        fromId: String,
        toId: String,
        sourceName: String,
    ) {
        // Exposed's update DSL cannot express SET col = COALESCE(col, ?) as an
        // assignment — raw SQL is the precise tool here.
        TransactionManager.current().exec(
            stmt =
                "UPDATE book_contributors " +
                    "SET credited_as = COALESCE(credited_as, ?), contributor_id = ? " +
                    "WHERE contributor_id = ?",
            args =
                listOf(
                    TextColumnType() to sourceName,
                    TextColumnType() to toId,
                    TextColumnType() to fromId,
                ),
        )
    }

    /**
     * Re-links junction rows matching `(contributor_id = fromContributorId AND
     * credited_as = aliasName)` to [toContributorId] and clears `credited_as`.
     *
     * The `credited_as` override is cleared because the new contributor's canonical
     * name IS [aliasName] — the per-book override is no longer needed once the rows
     * belong to the newly split-out contributor.
     *
     * Must be called inside a `suspendTransaction { ... }` block.
     */
    fun relinkByCreditedAs(
        fromContributorId: String,
        aliasName: String,
        toContributorId: String,
    ) {
        TransactionManager.current().exec(
            stmt =
                "UPDATE book_contributors " +
                    "SET contributor_id = ?, credited_as = NULL " +
                    "WHERE contributor_id = ? AND credited_as = ?",
            args =
                listOf(
                    TextColumnType() to toContributorId,
                    TextColumnType() to fromContributorId,
                    TextColumnType() to aliasName,
                ),
        )
    }

    /**
     * Enumerates distinct book IDs whose junction row matches
     * `(contributor_id = id AND credited_as = aliasName)`. Used by
     * `unmergeContributor` to enumerate affected books before the relink so the
     * service can re-upsert their search index entries afterward.
     *
     * Must be called inside a `suspendTransaction { ... }` block.
     */
    fun bookIdsForContributorWithCreditedAs(
        id: String,
        aliasName: String,
    ): List<String> =
        selectAll()
            .where { (contributorId eq id) and (creditedAs eq aliasName) }
            .map { it[bookId] }
            .distinct()
}
