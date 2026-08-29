package com.calypsan.listenup.client.domain.bulkedit

import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookMutation
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.api.dto.BookUpdate
import com.calypsan.listenup.client.domain.model.BookDetail
import com.calypsan.listenup.core.ContributorId
import com.calypsan.listenup.core.GenreId
import com.calypsan.listenup.core.SeriesId

/**
 * Turns the user's instructions into the concrete work one book needs — and nothing more.
 *
 * Pure, so the preview the user approves and the changes that get applied are computed by the same
 * code from the same inputs. There is no second path that could disagree with the count on screen.
 *
 * Returns an **empty list when the book already satisfies every instruction**. That is not
 * tidiness: without it, forty selected books produce forty mutations, forty outbox rows and forty
 * sync frames to every other device, most of which change nothing.
 */
internal fun List<BulkEdit>.actionsFor(book: BookDetail): List<BulkAction> =
    buildList {
        scalarUpdate(book)?.let { add(BulkAction.Mutate(it)) }
        seriesMutation(book)?.let { add(BulkAction.Mutate(it)) }
        contributorMutation(book)?.let { add(BulkAction.Mutate(it)) }
        genreMutation(book)?.let { add(BulkAction.Mutate(it)) }
        addAll(tagActions(book))
        addAll(moodActions(book))
    }

/**
 * Publisher, year and language folded into one patch.
 *
 * Merged rather than emitted separately because all three land in the same [BookMutation.Update] —
 * three instructions would otherwise become three outbox rows for one logical edit. Each field is
 * included only when it actually differs, so restating a value the book already has cannot count as
 * a change.
 */
private fun List<BulkEdit>.scalarUpdate(book: BookDetail): BookMutation.Update? {
    val publisher =
        filterIsInstance<BulkEdit.SetPublisher>().lastOrNull()?.publisher?.takeIf { it != book.publisher }
    val year = filterIsInstance<BulkEdit.SetPublishYear>().lastOrNull()?.year?.takeIf { it != book.publishYear }
    val language = filterIsInstance<BulkEdit.SetLanguage>().lastOrNull()?.language?.takeIf { it != book.language }

    if (publisher == null && year == null && language == null) return null
    return BookMutation.Update(
        BookUpdate(publisher = publisher, publishYear = year, language = language),
    )
}

/**
 * The book's existing series plus any new ones.
 *
 * `SetSeries` is a replace-set, so the union has to be built by hand: existing memberships are
 * converted back to inputs — keeping their positions — and only genuinely new names are appended.
 * Matching is by name because a series the user picked may not carry an id yet.
 */
private fun List<BulkEdit>.seriesMutation(book: BookDetail): BookMutation.SetSeries? {
    val additions = filterIsInstance<BulkEdit.AddToSeries>().map { it.series }
    if (additions.isEmpty()) return null

    val existingNames = book.series.map { it.seriesName }.toSet()
    val new = additions.filter { it.name !in existingNames }.distinctBy { it.name }
    if (new.isEmpty()) return null

    val existing =
        book.series.map {
            BookSeriesInput(id = SeriesId(it.seriesId), name = it.seriesName, position = it.sequence)
        }
    return BookMutation.SetSeries(existing + new)
}

/**
 * The book's existing credits plus any new ones, renumbered.
 *
 * The wire shape is one row per (contributor, role) with an explicit position, while the domain
 * holds one row per contributor with a list of roles — so existing credits are flattened before the
 * new ones are appended. Positions are reassigned across the whole list because they must be
 * contiguous from zero for the server's ordering to be right.
 */
private fun List<BulkEdit>.contributorMutation(book: BookDetail): BookMutation.SetContributors? {
    val additions = filterIsInstance<BulkEdit.AddContributors>().flatMap { it.contributors }
    if (additions.isEmpty()) return null

    val existingPairs = book.allContributors.flatMap { c -> c.roles.map { role -> c.name to role } }.toSet()
    val new = additions.filterNot { Pair(it.name, it.role) in existingPairs }.distinctBy { it.name to it.role }
    if (new.isEmpty()) return null

    val existing =
        book.allContributors.flatMap { c ->
            c.roles.map { role ->
                BookContributorInput(
                    id = ContributorId(c.id),
                    name = c.name,
                    role = role,
                    creditedAs = c.creditedAs,
                    position = 0,
                )
            }
        }
    return BookMutation.SetContributors(
        (existing + new).mapIndexed { index, input -> input.copy(position = index) },
    )
}

/** The book's existing genres plus any new ones. `SetGenres` replaces, so the union is built here. */
private fun List<BulkEdit>.genreMutation(book: BookDetail): BookMutation.SetGenres? {
    val additions = filterIsInstance<BulkEdit.AddGenres>().flatMap { it.genres }
    if (additions.isEmpty()) return null

    val existingIds = book.genres.map { it.id }.toSet()
    val new = additions.filter { it.genreId.value !in existingIds }.distinctBy { it.genreId.value }
    if (new.isEmpty()) return null

    val existing = book.genres.map { BookGenreInput(genreId = GenreId(it.id)) }
    return BookMutation.SetGenres(existing + new)
}

/**
 * One action per tag the book does not already carry.
 *
 * Unlike genres, `addTagToBook` is inherently additive, so there is no read-merge-write here — the
 * union is free and only the genuinely-new slugs need naming.
 */
private fun List<BulkEdit>.tagActions(book: BookDetail): List<BulkAction.AddTag> {
    val existing = book.tags.map { it.slug }.toSet()
    return filterIsInstance<BulkEdit.AddTags>()
        .flatMap { it.slugs }
        .distinct()
        .filter { it !in existing }
        .map { BulkAction.AddTag(it) }
}

/** One action per mood the book does not already carry. Additive, like tags. */
private fun List<BulkEdit>.moodActions(book: BookDetail): List<BulkAction.AddMood> {
    val existing = book.moods.map { it.slug }.toSet()
    return filterIsInstance<BulkEdit.AddMoods>()
        .flatMap { it.slugs }
        .distinct()
        .filter { it !in existing }
        .map { BulkAction.AddMood(it) }
}
