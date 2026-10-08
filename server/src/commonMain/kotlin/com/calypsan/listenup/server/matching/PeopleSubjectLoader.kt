package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.auth.UserPrincipal
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.metadata.spi.PersonLibraryBook
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.ExternalRefKind
import com.calypsan.listenup.server.services.readExternalRefs

/**
 * Reads a people Find's subject: the contributor, their refs, and the live books crediting them in any role that
 * [caller] can see (decision D12: a restricted title never leaks through a person search), each with its ASIN,
 * ISBN, refs and the roles the contributor holds on it. Also the store to search in: the library's, else the
 * server default.
 */
internal class PeopleSubjectLoader(
    private val db: ListenUpDatabase,
    private val contributors: ContributorRepository,
    private val accessPolicy: BookAccessPolicy,
) {
    /** [contributorId]'s subject, or null when there is no live contributor with that id. */
    suspend fun load(
        contributorId: ContributorId,
        caller: UserPrincipal,
    ): PeopleSubject? {
        val contributor = contributors.findById(contributorId.value)?.takeIf { it.deletedAt == null } ?: return null
        val visible = accessPolicy.accessibleBookIds(caller.userId.value, caller.role)
        val books =
            suspendTransaction(db) {
                val credits =
                    db.bookContributorsQueries
                        .creditedBooksForContributor(contributorId.value)
                        .executeAsList()
                        .filter { visible == null || it.id in visible }
                val rows = credits.distinctBy { it.id }
                val roles =
                    credits
                        .groupBy({ it.id }) { ContributorRole.fromApiValue(it.role) }
                        .mapValues { (_, roles) -> roles.filterNotNull().toSet() }
                val refs =
                    rows
                        .map { it.id }
                        .chunked(
                            SQLITE_IN_CHUNK,
                        ).fold(emptyMap<String, List<ExternalRef>>()) { all, chunk ->
                            all + db.readExternalRefs(ExternalRefKind.BOOK, chunk)
                        }
                rows.map { row ->
                    PersonLibraryBook(
                        bookId = row.id,
                        title = row.title,
                        asin = row.asin?.run { trim().takeIf { it.isNotEmpty() } },
                        isbn = row.isbn?.run { trim().takeIf { it.isNotEmpty() } },
                        refs = refs[row.id].orEmpty(),
                        roles = roles[row.id].orEmpty(),
                    )
                }
            }
        return PeopleSubject(
            contributorId = contributor.id,
            name = contributor.name,
            refs = contributor.externalRefs,
            books = books,
        )
    }

    /** The store a people Find searches: the library's, else the server default. */
    suspend fun region(): MetadataLocale =
        suspendTransaction(db) {
            db.librariesQueries.selectFirstLiveId().executeAsOneOrNull()?.let { id ->
                db.librariesQueries
                    .selectMetadataRegion(id)
                    .executeAsOneOrNull()
                    ?.metadata_region
            }
        }?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let(::MetadataLocale) ?: MetadataLocale.DEFAULT

    private companion object {
        const val SQLITE_IN_CHUNK = 900
    }
}
