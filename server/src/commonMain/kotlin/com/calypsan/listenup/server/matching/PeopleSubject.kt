package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.metadata.spi.PersonLibraryBook
import com.calypsan.listenup.server.metadata.spi.PersonLookup
import com.calypsan.listenup.server.metadata.spi.presentedAs

/** How many of the person's books a source reads the credits of (spec, people Find step 2). */
internal const val MAX_LOOKUP_BOOKS = 5

/**
 * The person a people Find is for: the contributor, their refs, and every [books] in this library (that the
 * caller can see) crediting them, in any role.
 */
internal data class PeopleSubject(
    val contributorId: String,
    val name: String,
    val refs: List<ExternalRef>,
    val books: List<PersonLibraryBook>,
) {
    /** The books whose credits a source can read: those with an identifier, at most [MAX_LOOKUP_BOOKS]. */
    val lookupBooks: List<PersonLibraryBook>
        get() = books.filter { it.isIdentified() }.take(MAX_LOOKUP_BOOKS)

    /** What [provider] is asked: its own refs for the person, these books, and [query] or the person's name. */
    fun lookupFor(
        provider: MetadataProviderId,
        query: String?,
    ): PersonLookup =
        PersonLookup(
            name = query ?: name,
            keys = refs.filter { it.provider == provider.presentedAs().value }.map { it.id },
            books = lookupBooks,
        )
}

/** A book a catalogue can recognise: it has an ASIN, an ISBN or a ref. */
internal fun PersonLibraryBook.isIdentified(): Boolean = asin != null || isbn != null || refs.isNotEmpty()
