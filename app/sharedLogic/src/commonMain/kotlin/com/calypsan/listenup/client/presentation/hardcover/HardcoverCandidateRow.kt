package com.calypsan.listenup.client.presentation.hardcover

import com.calypsan.listenup.api.dto.hardcover.HardcoverBookCandidate

/**
 * One Hardcover search result as Find on Hardcover shows it: what it is ([title], [authors],
 * [releaseYear]) and what tells the real book from a third-party summary of it — [ratingsCount],
 * whether Hardcover names an audiobook edition, and whether it [sharesAuthor] with the ListenUp book.
 */
data class HardcoverCandidateRow(
    val hcBookId: Long,
    val hcEditionId: Long?,
    val title: String,
    val authors: List<String>,
    val releaseYear: Int?,
    val ratingsCount: Int?,
    val hasAudiobookEdition: Boolean,
    val sharesAuthor: Boolean,
)

/**
 * [candidates] as rows, those sharing an author with [bookAuthors] first and otherwise in Hardcover's
 * own order (a stable sort). A title-and-author query ranks summaries above the real book, so the
 * query is the title alone and the author re-ranks here, for free.
 */
internal fun rankCandidates(
    candidates: List<HardcoverBookCandidate>,
    bookAuthors: List<String>,
): List<HardcoverCandidateRow> {
    val wanted = bookAuthors.map(::comparableName).filter { it.isNotEmpty() }.toSet()
    return candidates
        .map { candidate ->
            HardcoverCandidateRow(
                hcBookId = candidate.hcBookId,
                hcEditionId = candidate.hcEditionId,
                title = candidate.title,
                authors = candidate.authors,
                releaseYear = candidate.releaseYear,
                ratingsCount = candidate.ratingsCount,
                hasAudiobookEdition = candidate.hcEditionId != null,
                sharesAuthor = candidate.authors.any { comparableName(it) in wanted },
            )
        }.sortedByDescending { it.sharesAuthor }
}

/** A name with case, spacing and punctuation gone: "Andy  Weir." and "andy weir" compare equal. */
private fun comparableName(name: String): String = name.lowercase().filter { it.isLetterOrDigit() }
