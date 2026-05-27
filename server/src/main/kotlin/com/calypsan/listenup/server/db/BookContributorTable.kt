package com.calypsan.listenup.server.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll

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
}
