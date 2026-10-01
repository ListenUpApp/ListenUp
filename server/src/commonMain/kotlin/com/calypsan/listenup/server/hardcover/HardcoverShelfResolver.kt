package com.calypsan.listenup.server.hardcover

import com.calypsan.listenup.api.dto.auth.UserRole
import com.calypsan.listenup.api.dto.hardcover.HardcoverMatchMethod
import com.calypsan.listenup.server.absimport.normalizeText
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.auth.UserRoleLookup
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction

/** Which library book a pulled shelf entry is. */
sealed interface ShelfResolution {
    /** The ListenUp book the entry's reads belong to. */
    val bookId: String

    /** The book is already linked to this Hardcover book. */
    data class Linked(
        override val bookId: String,
    ) : ShelfResolution

    /** Matched just now from the entry's own identifiers or title; [match] becomes the book's link. */
    data class Matched(
        override val bookId: String,
        val match: HardcoverMatch,
    ) : ShelfResolution
}

/** The reverse match's tiers, most certain first. */
private val REVERSE_TIERS = listOf(HardcoverMatchMethod.ASIN, HardcoverMatchMethod.ISBN, HardcoverMatchMethod.SEARCH)

/**
 * Resolves the pull's shelf entries (spec B3) to the user's library books. A book LINKED to the
 * entry's Hardcover book wins. Otherwise, for an entry with a finished read to write or one on Want to
 * Read (#1539), a local reverse match runs — the logged edition's ASIN, then its ISBNs, then an exact normalised title with at least
 * one exact author — the way the ABS importer's `BookMatcher` does: exactly one book the user may see
 * wins, more than one stops the search. The author test accepts ANY of the entry's contributors:
 * Hardcover lists illustrators and translators with no role, sometimes first (Alice's John Tenniel).
 * It never guesses, never takes a book that already has a link row (a NEEDS_MATCH book, or one the
 * user unlinked with "Change match", is the user's to settle), and costs no Hardcover request: the
 * page carries everything it compares.
 */
class HardcoverShelfResolver(
    private val sql: ListenUpDatabase,
    private val access: BookAccessPolicy,
) {
    private val roles = UserRoleLookup(sql)

    /** [entries] (one page) resolved, keyed by user-book id. An entry that resolves to nothing is absent. */
    suspend fun resolve(
        userId: String,
        entries: List<HardcoverShelfEntry>,
    ): Map<Long, ShelfResolution> {
        val role = roles.roleOf(userId) ?: return emptyMap()
        val titles = TitleIndex(sql)
        val claimed = HashSet<String>()
        val resolved = LinkedHashMap<Long, ShelfResolution>()
        for (entry in entries) {
            val linked =
                suspendTransaction(sql) {
                    sql.hardcoverBookLinksQueries.linkedBooksFor(userId, entry.hcBookId).executeAsList()
                }
            val resolution =
                when {
                    linked.size == 1 -> {
                        ShelfResolution.Linked(linked.single())
                    }

                    linked.isEmpty() &&
                        (entry.finishedReads.isNotEmpty() || entry.statusId == HardcoverStatus.WANT_TO_READ) -> {
                        reverseMatch(
                            userId,
                            role,
                            entry,
                            titles,
                            claimed,
                        )
                    }

                    else -> {
                        null
                    }
                }
            if (resolution != null && claimed.add(resolution.bookId)) resolved[entry.userBookId] = resolution
        }
        return resolved
    }

    private suspend fun reverseMatch(
        userId: String,
        role: UserRole,
        entry: HardcoverShelfEntry,
        titles: TitleIndex,
        claimed: Set<String>,
    ): ShelfResolution.Matched? {
        for (method in REVERSE_TIERS) {
            val accessible =
                candidatesFor(
                    method,
                    entry,
                    titles,
                ).distinct().filter { access.canAccess(userId, role, it) }
            when {
                accessible.isEmpty() -> {
                    continue
                }

                // Ambiguous: stop, never guess.
                accessible.size > 1 -> {
                    return null
                }

                else -> {
                    val bookId = accessible.single()
                    val taken =
                        bookId in claimed ||
                            suspendTransaction(
                                sql,
                            ) { sql.hardcoverBookLinksQueries.hasLink(userId, bookId).executeAsOne() }
                    return if (taken) {
                        null
                    } else {
                        ShelfResolution.Matched(
                            bookId,
                            HardcoverMatch(entry.hcBookId, entry.defaultAudioEditionId, method),
                        )
                    }
                }
            }
        }
        return null
    }

    private suspend fun candidatesFor(
        method: HardcoverMatchMethod,
        entry: HardcoverShelfEntry,
        titles: TitleIndex,
    ): List<String> =
        when (method) {
            HardcoverMatchMethod.ASIN -> {
                entry.editionAsin
                    ?.let { asin ->
                        suspendTransaction(sql) { sql.booksQueries.selectLiveIdsByAsin(asin).executeAsList() }
                    }.orEmpty()
            }

            HardcoverMatchMethod.ISBN -> {
                suspendTransaction(
                    sql,
                ) { entry.editionIsbns.flatMap { sql.booksQueries.selectLiveIdsByIsbn(it).executeAsList() } }
            }

            HardcoverMatchMethod.SEARCH -> {
                titleAndAuthor(entry, titles)
            }

            HardcoverMatchMethod.MANUAL -> {
                emptyList()
            }
        }

    /** Books titled exactly as the entry (normalised) whose authors include ANY of the entry's contributors. */
    private suspend fun titleAndAuthor(
        entry: HardcoverShelfEntry,
        titles: TitleIndex,
    ): List<String> {
        val title = entry.title?.let(::normalizeText)?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val contributors =
            entry.authors
                .map(::normalizeText)
                .filter { it.isNotEmpty() }
                .toSet()
        if (contributors.isEmpty()) return emptyList()
        val ids = titles.idsTitled(title)
        if (ids.isEmpty()) return emptyList()
        val authorsByBook =
            suspendTransaction(sql) { sql.bookContributorsQueries.authorNamesForBooks(ids).executeAsList() }
                .groupBy({ it.book_id }, { normalizeText(it.name) })
        return ids.filter { id -> authorsByBook[id].orEmpty().any { it in contributors } }
    }

    /** Every live book's id by normalised title, read once per page and only if a title match is needed. */
    private class TitleIndex(
        private val sql: ListenUpDatabase,
    ) {
        private var byTitle: Map<String, List<String>>? = null

        suspend fun idsTitled(normalized: String): List<String> {
            val index =
                byTitle ?: suspendTransaction(sql) { sql.booksQueries.selectLiveIdsAndTitles().executeAsList() }
                    .groupBy({ normalizeText(it.title) }, { it.id })
                    .also { byTitle = it }
            return index[normalized].orEmpty()
        }
    }
}
