package com.calypsan.listenup.server.metadata.spi

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.metadata.MetadataLocale
import com.calypsan.listenup.api.result.AppResult

/**
 * One of this library's books crediting the person, with what identifies it at a catalogue and the [roles] the
 * person holds on it here.
 */
data class PersonLibraryBook(
    val bookId: String,
    val title: String,
    val asin: String?,
    val isbn: String?,
    val refs: List<ExternalRef>,
    val roles: Set<ContributorRole>,
)

/**
 * Everything a people Find knows, handed to one source: the [name] to search (the contributor's, or what the
 * person typed), this source's own [keys] for the person (their refs here), and up to five library [books]
 * crediting them in any role — whose credits at the source name the person. A Find has no role: the person is
 * looked for in every role the source knows.
 */
data class PersonLookup(
    val name: String,
    val keys: List<String>,
    val books: List<PersonLibraryBook>,
)

/**
 * How alike a person found only through your books' credits must be named to be shown — otherwise they are a
 * co-author or co-narrator of those books, not the person (a source needn't read such a co-credit's profile).
 */
internal const val CO_CREDIT_NAME_SIMILARITY = 0.5

/** Which of a people Find's steps a source ran. */
enum class PersonStep {
    /** It resolved the person's existing ref. */
    LINK,

    /** It read the credits of the person's books. */
    VIA_BOOKS,

    /** It searched by name. */
    NAME,
}

/**
 * One person a source found. [key] is the source's own id for them (a ref id: an ASIN, a Hardcover author id);
 * [roles] are the roles the source credits them in; [knownWorks] are up to two titles and [worksCount] how many
 * books the source credits them on; [creditedBookIds] are the lookup's books the source credits them on, in any
 * role; [viaLink] marks the person's existing ref; [foundByName] marks a hit of the name search (a
 * person found only through your books' credits is a co-credit candidate, kept only when their name matches).
 */
data class FoundPerson(
    val key: String,
    val name: String,
    val roles: Set<ContributorRole>,
    val photoUrl: String? = null,
    val knownWorks: List<String> = emptyList(),
    val worksCount: Int? = null,
    val creditedBookIds: Set<String> = emptySet(),
    val viaLink: Boolean = false,
    val foundByName: Boolean = false,
)

/** A source's answer to one people Find. */
data class PersonAnswer(
    val people: List<FoundPerson>,
    val steps: Set<PersonStep>,
)

/**
 * People Find (matching redesign PR 4): look a person up by everything we know in one go — their ref here, the
 * credits of their books, then their name — so each source batches its own calls. Every people source is asked,
 * whatever roles brought the person into the library. The return convention is [MetadataCapability]'s; a rate limit is
 * `MetadataError.ExternalRateLimited` with its retry-after when the source gave one.
 */
interface PersonFindSource : ContributorSource {
    /** Whether this source can take part right now — checked before every Find, never cached. */
    suspend fun personAvailability(): FindAvailability = FindAvailability.Available

    /** Finds [lookup]'s person in [locale]. */
    suspend fun findPeople(
        lookup: PersonLookup,
        locale: MetadataLocale,
    ): AppResult<PersonAnswer>
}
