package com.calypsan.listenup.server.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table

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
}
