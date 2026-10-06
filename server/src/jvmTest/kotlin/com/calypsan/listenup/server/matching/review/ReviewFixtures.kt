package com.calypsan.listenup.server.matching.review

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.server.metadata.ComposedOptions
import com.calypsan.listenup.server.metadata.spi.BookContributorMeta
import com.calypsan.listenup.server.metadata.spi.BookCoreMeta
import com.calypsan.listenup.server.metadata.spi.MetadataProviderId
import com.calypsan.listenup.server.testing.bookPayloadFixture

internal val AUDIBLE = MetadataProviderId.AUDIBLE
internal val AUDNEXUS = MetadataProviderId.AUDNEXUS
internal val HARDCOVER = MetadataProviderId.HARDCOVER
internal val ITUNES = MetadataProviderId.ITUNES

/** Your copy as Review's tests see it: Project Hail Mary with a few gaps. */
internal fun yourBook(
    title: String = "Project Hail Mary",
    description: String? = null,
    publishYear: Int? = 2021,
    authors: List<String> = listOf("Andy Weir"),
): BookSyncPayload =
    bookPayloadFixture(
        id = "b1",
        title = title,
        contributors =
            authors.mapIndexed { i, name -> BookContributorPayload("a$i", name, null, ContributorRole.AUTHOR.apiValue, null) },
    ).copy(description = description, publishYear = publishYear, revision = 7L)

/** A provider's core with the given text fields. */
internal fun core(
    title: String? = null,
    description: String? = null,
    publisher: String? = null,
    releaseDate: String? = null,
    authors: List<String> = emptyList(),
    narrators: List<String> = emptyList(),
): BookCoreMeta =
    BookCoreMeta(
        title = title,
        description = description,
        publisher = publisher,
        releaseDate = releaseDate,
        authors = authors.map { BookContributorMeta(name = it, role = ContributorRole.AUTHOR) },
        narrators = narrators.map { BookContributorMeta(name = it, role = ContributorRole.NARRATOR) },
    )

internal fun options(vararg cores: Pair<MetadataProviderId, BookCoreMeta>): ComposedOptions =
    ComposedOptions(cores = cores.toMap(), coreAsked = cores.map { it.first }.toSet(), coreFailures = emptyMap())
