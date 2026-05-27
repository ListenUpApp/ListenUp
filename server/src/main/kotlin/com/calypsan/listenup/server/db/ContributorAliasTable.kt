package com.calypsan.listenup.server.db

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Server-side storage for the `aliases: List<String>` field embedded on
 * [com.calypsan.listenup.api.sync.ContributorSyncPayload].
 *
 * Aliases are the normalised backing rows; the payload's `aliases` field is the
 * denormalised wire view. [com.calypsan.listenup.server.services.ContributorRepository.readPayload]
 * joins this table to assemble the payload; `writePayload` calls [replaceForContributor]
 * to atomically replace the alias set on every contributor write — matches the
 * embedded-array contract.
 *
 * The table cascades on contributor hard-delete (`ON DELETE CASCADE`). Soft-deletes
 * leave the alias rows in place; they are harmless because callers filter contributors
 * by `deleted_at IS NULL` at the query layer.
 */
internal object ContributorAliasTable : Table("contributor_aliases") {
    val contributorId = reference("contributor_id", ContributorTable.id, onDelete = ReferenceOption.CASCADE)
    val alias = varchar("alias", 500)

    override val primaryKey = PrimaryKey(contributorId, alias)

    init {
        index("idx_contributor_aliases_contributor_id", false, contributorId)
    }

    /**
     * Returns the live aliases for [id] in insertion order. Returns an empty list when
     * the contributor has no aliases. Must be called inside a `suspendTransaction { }` block.
     */
    fun aliasesFor(id: String): List<String> =
        selectAll()
            .where { contributorId eq id }
            .map { it[alias] }

    /**
     * Atomically replaces the alias set for [id] with [aliases] via delete-then-insert.
     * Called by [com.calypsan.listenup.server.services.ContributorRepository.writePayload]
     * on every contributor write to mirror the wire payload's embedded array.
     *
     * Must be called inside a `suspendTransaction { }` block.
     */
    fun replaceForContributor(
        id: String,
        aliases: List<String>,
    ) {
        deleteWhere { contributorId eq id }
        aliases.forEach { value ->
            insert {
                it[contributorId] = id
                it[alias] = value
            }
        }
    }
}
