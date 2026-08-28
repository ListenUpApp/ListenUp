package com.calypsan.listenup.client.domain.bulkedit

import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookMutation
import com.calypsan.listenup.api.dto.BookSeriesInput

/**
 * One thing the user asked to change across a selection.
 *
 * A bulk edit is a **sparse list of these**, never a record of every field. A field the user did not
 * touch produces no instruction and therefore cannot be written — which is the structural answer to
 * the failure every bulk-edit UI has, where saving blanks the fields nobody edited.
 *
 * The verb is in the name. `Set*` replaces, `Add*` unions with what the book already has. No field
 * is ambiguous about which it does, and no runtime flag decides, so a call site cannot be misread.
 */
sealed interface BulkEdit {
    /** Replaces the publisher. */
    data class SetPublisher(
        val publisher: String,
    ) : BulkEdit

    /** Replaces the publication year. */
    data class SetYear(
        val year: Int,
    ) : BulkEdit

    /** Replaces the language. */
    data class SetLanguage(
        val language: String,
    ) : BulkEdit

    /**
     * Adds a series membership.
     *
     * Series is multi-valued — a book can belong to several — so this unions rather than replaces.
     * [BookSeriesInput.position] is left null: sequence is inherently per-book, and one value across
     * forty books would make them all "Book 1".
     */
    data class AddSeries(
        val series: BookSeriesInput,
    ) : BulkEdit

    /** Adds contributors, keeping the ones already credited. */
    data class AddContributors(
        val contributors: List<BookContributorInput>,
    ) : BulkEdit

    /** Adds genres, keeping the ones already set. */
    data class AddGenres(
        val genres: List<BookGenreInput>,
    ) : BulkEdit

    /** Adds tags by slug. Tags travel their own repository, not [BookMutation]. */
    data class AddTags(
        val slugs: List<String>,
    ) : BulkEdit

    /** Adds moods by slug. Moods travel their own repository, not [BookMutation]. */
    data class AddMoods(
        val slugs: List<String>,
    ) : BulkEdit
}

/**
 * One concrete change to make to one book.
 *
 * Two roads reach a book's metadata: most fields go through [BookMutation] and
 * `BookEditRepository`, while tags and moods go through their own repositories. Both are
 * offline-first through `OfflineEditor`; they are simply different doors. This type is what lets a
 * single pure function describe work on either road.
 */
sealed interface BulkAction {
    /** A change expressible as a [BookMutation]. */
    data class Mutate(
        val mutation: BookMutation,
    ) : BulkAction

    /** Associate a tag, by slug. */
    data class AddTag(
        val slug: String,
    ) : BulkAction

    /** Associate a mood, by slug. */
    data class AddMood(
        val slug: String,
    ) : BulkAction
}
