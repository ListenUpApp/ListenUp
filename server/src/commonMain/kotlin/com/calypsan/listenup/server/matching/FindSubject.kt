package com.calypsan.listenup.server.matching

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.match.EditionFormat
import com.calypsan.listenup.api.dto.match.ExternalRef
import com.calypsan.listenup.api.dto.match.YourCopy
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.server.metadata.spi.BookIdentity
import com.calypsan.listenup.server.metadata.spi.FindKey
import com.calypsan.listenup.server.metadata.spi.FindLookup
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId

/** The book a Find is for, reduced to what Find searches by and ranks against. */
internal data class FindSubject(
    val bookId: String,
    val title: String,
    val primaryAuthor: String?,
    val narrators: List<String>,
    val durationMs: Long?,
    val chapterCount: Int?,
    val year: Int?,
    val format: EditionFormat?,
    val asin: String?,
    val isbn: String?,
    val language: String?,
    val refs: List<ExternalRef>,
) {
    /** Title and primary author — the query when the person typed none. */
    fun searchText(): String = listOfNotNull(title, primaryAuthor).joinToString(" ")

    /** What the scorer compares a candidate with. */
    fun identity(): BookIdentity =
        BookIdentity(title = title, primaryAuthor = primaryAuthor, durationMs = durationMs, narrators = narrators)

    /** The strip every candidate is ranked against. */
    fun yourCopy(): YourCopy = YourCopy(durationMs, narrators, chapterCount, year, format)

    /** What [provider] is asked: its own refs (unless [identify] is off), the book's identifiers, and [text]. */
    fun lookupFor(
        provider: MetadataProviderId,
        identify: Boolean,
        text: String,
    ): FindLookup =
        FindLookup(
            bookId = bookId,
            identify = identify,
            keys =
                if (identify) {
                    refs.filter { it.provider == provider.value }.map { FindKey(it.id, it.region) }
                } else {
                    emptyList()
                },
            asin = asin.takeIf { identify },
            isbn = isbn.takeIf { identify },
            text = text,
            title = title,
            primaryAuthor = primaryAuthor,
        )
}

/** A book as Find sees it. Your copy's format is abridged when the book says so, otherwise unabridged. */
internal fun BookSyncPayload.toFindSubject(): FindSubject {
    fun named(role: ContributorRole) =
        contributors.filter { ContributorRole.fromApiValue(it.role) == role }.map { it.name }
    return FindSubject(
        bookId = id,
        title = title,
        primaryAuthor = named(ContributorRole.AUTHOR).firstOrNull(),
        narrators = named(ContributorRole.NARRATOR),
        durationMs = totalDuration.takeIf { it > 0 },
        chapterCount = chapters.size.takeIf { it > 0 },
        year = publishYear,
        format = if (abridged) EditionFormat.ABRIDGED else EditionFormat.UNABRIDGED,
        asin = asin?.takeIf { it.isNotBlank() },
        isbn = isbn?.takeIf { it.isNotBlank() },
        language = language,
        refs = externalRefs,
    )
}
