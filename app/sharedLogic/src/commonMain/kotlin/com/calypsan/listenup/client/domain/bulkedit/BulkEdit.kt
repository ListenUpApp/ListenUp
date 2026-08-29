package com.calypsan.listenup.client.domain.bulkedit

import com.calypsan.listenup.api.dto.BookContributorInput
import com.calypsan.listenup.api.dto.BookGenreInput
import com.calypsan.listenup.api.dto.BookMutation
import com.calypsan.listenup.api.dto.BookSeriesInput
import com.calypsan.listenup.api.dto.BookUpdate

/**
 * Rejects an `Add*` instruction that names nothing to add.
 *
 * Takes [field] so the failure names the empty list. A shared message could not — and "an
 * instruction with nothing to add" tells whoever reads the crash nothing about which one.
 */
private fun requireNotEmpty(
    values: List<*>,
    field: String,
) {
    require(
        values.isNotEmpty(),
    ) { "$field must not be empty: an instruction with nothing to add is not an instruction" }
}

/**
 * One thing the user asked to change across a selection.
 *
 * A bulk edit is a **sparse list of these**, never a record of every field. A field the user did not
 * touch produces no instruction and therefore cannot be written — which is the structural answer to
 * the failure every bulk-edit UI has, where saving blanks the fields nobody edited.
 *
 * The verb is in the name. `Set*` replaces, `Add*` unions with what the book already has. No field
 * is ambiguous about which it does, and no runtime flag decides — a call site cannot be misread.
 *
 * When one field carries several instructions the verb decides the tie-break: the **last** `Set*`
 * wins, because a second one is the user correcting the first; **every** `Add*` is unioned, because
 * adding never removes. Within that union a duplicate resolves to its **first** occurrence — the
 * later spelling's extra detail (a `creditedAs`, a series position) is discarded, which is safe only
 * because the fields that would differ meaningfully are normalised away before the comparison.
 *
 * Every instruction validates in `init`, so an unusable value fails where the user typed it rather
 * than inside the planner, once per selected book. Note what that forbids: no instruction can carry
 * a blank, so clearing a field across a selection has no expression in this pass.
 */
sealed interface BulkEdit {
    /** Replaces the publisher. */
    data class SetPublisher(
        val publisher: String,
    ) : BulkEdit {
        init {
            require(publisher.isNotBlank()) { "publisher must not be blank" }
            require(publisher.length <= BookUpdate.MAX_PUBLISHER) {
                "publisher must be <= ${BookUpdate.MAX_PUBLISHER} chars"
            }
        }
    }

    /** Replaces the publication year. */
    data class SetPublishYear(
        val year: Int,
    ) : BulkEdit {
        init {
            require(year in BookUpdate.MIN_YEAR..BookUpdate.MAX_YEAR) {
                "year must be in ${BookUpdate.MIN_YEAR}..${BookUpdate.MAX_YEAR}"
            }
        }
    }

    /** Replaces the language. */
    data class SetLanguage(
        val language: String,
    ) : BulkEdit {
        init {
            require(language.isNotBlank()) { "language must not be blank" }
            require(language.length <= BookUpdate.MAX_LANGUAGE) {
                "language must be <= ${BookUpdate.MAX_LANGUAGE} chars"
            }
        }
    }

    /**
     * Adds a series membership.
     *
     * This unions rather than replaces: a book can belong to several series, and replacing the whole
     * list would destroy memberships the user never saw or intended to touch.
     * [BookSeriesInput.position] is **dropped by the planner**, whatever this instruction carries:
     * sequence is inherently per-book, and one value across forty books would make them all
     * "Book 1". [BookSeriesInput.isPrimary] passes through untouched but is inert — the server has
     * no `is_primary` column and drops it.
     */
    data class AddToSeries(
        val series: BookSeriesInput,
    ) : BulkEdit

    /**
     * Adds contributors, keeping the ones already credited.
     *
     * [BookContributorInput.position] is ignored: it is a per-book ordinal, and the planner renumbers
     * the whole credit list against each book so positions stay contiguous from zero.
     *
     * [BookContributorInput.role] is matched and written case-insensitively, canonicalised to the
     * lowercase token [com.calypsan.listenup.api.dto.ContributorRole.apiValue] uses — the same string
     * the single-book edit writes, and the string the junction table's `(book, contributor, role)`
     * key compares. Where the single-book path *drops* a role its enum does not recognise, this one
     * deliberately keeps it: a bulk edit must not be the thing that silently discards a credit the
     * library already contains.
     */
    data class AddContributors(
        val contributors: List<BookContributorInput>,
    ) : BulkEdit {
        init {
            requireNotEmpty(contributors, "contributors")
        }
    }

    /** Adds genres, keeping the ones already set. */
    data class AddGenres(
        val genres: List<BookGenreInput>,
    ) : BulkEdit {
        init {
            requireNotEmpty(genres, "genres")
        }
    }

    /** Adds tags by slug. */
    data class AddTags(
        val slugs: List<String>,
    ) : BulkEdit {
        init {
            requireNotEmpty(slugs, "slugs")
        }
    }

    /** Adds moods by slug. */
    data class AddMoods(
        val slugs: List<String>,
    ) : BulkEdit {
        init {
            requireNotEmpty(slugs, "slugs")
        }
    }
}

/**
 * One concrete change to make to one book.
 *
 * Most fields go through [BookMutation] and `BookEditRepository`, which is offline-first. Tags and
 * moods go through their own repositories, which are offline-first only for a tag or mood that
 * already exists locally — a brand-new one mints its id server-side and needs a connection. The bulk
 * form therefore offers existing tags and moods rather than free-text creation. This type is what
 * lets a single pure function describe work on either road.
 *
 * The type carries no book identity — which book an action applies to is the caller's context, not
 * this type's; `BulkEditApplier` is the next reader, pairing each action with its book.
 *
 * One [BulkAction] is exactly one repository call — the unit of failure and retry. That is why
 * `AddTags(slugs)` fans out to one [AddTag] per slug rather than carrying the whole list: a failure
 * partway through a batch must not silently swallow the slugs that came after it.
 *
 * Internal: produced by the planner and consumed by the applier, both within `:app:sharedLogic`.
 * Only [BulkEdit] crosses into `:app:sharedUI`.
 */
internal sealed interface BulkAction {
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
