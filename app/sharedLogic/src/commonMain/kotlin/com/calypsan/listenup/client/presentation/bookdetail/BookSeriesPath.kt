package com.calypsan.listenup.client.presentation.bookdetail

import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.domain.model.SeriesHierarchy
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesCrumb

/**
 * One line of a book's series path on Book Detail — "Cosmere › Mistborn › Mistborn Era 1 #1". Every
 * part is a link: each of [ancestors], then the series itself.
 *
 * @property sequence the book's number in [seriesName], formatted ("1", "1.5"); null when unnumbered.
 * @property ancestors the series above [seriesId], root first. Empty for a top-level series.
 */
data class BookSeriesPath(
    val seriesId: String,
    val seriesName: String,
    val sequence: String?,
    val ancestors: List<SeriesCrumb>,
)

/**
 * The path lines for a book's series [memberships], one per series, in membership order.
 *
 * A membership in a series that is an ancestor of another of the book's series is dropped: the
 * deeper line already names it ("Cosmere #4" adds nothing under "Cosmere › Mistborn #1").
 */
internal fun bookSeriesPaths(
    memberships: List<BookSeries>,
    hierarchy: SeriesHierarchy,
): List<BookSeriesPath> {
    val ancestorsOfMemberships =
        memberships.flatMapTo(
            HashSet(),
        ) { membership -> hierarchy.ancestorsOf(membership.seriesId).map { it.id.value } }
    return memberships
        .filterNot { it.seriesId in ancestorsOfMemberships }
        .distinctBy { it.seriesId }
        .map { membership ->
            BookSeriesPath(
                seriesId = membership.seriesId,
                seriesName = membership.seriesName,
                sequence = membership.sequenceLabel,
                ancestors =
                    hierarchy
                        .ancestorsOf(
                            membership.seriesId,
                        ).map { SeriesCrumb(id = it.id.value, name = it.name) },
            )
        }
}
