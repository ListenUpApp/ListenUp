package com.calypsan.listenup.client.domain.bulkedit

import com.calypsan.listenup.api.dto.BookMutation
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.repository.BookEditRepository
import com.calypsan.listenup.client.domain.repository.MoodRepository
import com.calypsan.listenup.client.domain.repository.TagRepository
import com.calypsan.listenup.core.BookId

/**
 * Carries out the actions [actionsFor] planned, for one book.
 *
 * Every call here is offline-first: `BookEditRepository` and both facet repositories route through
 * `OfflineEditor`, which writes the optimistic Room merge and enqueues the outbox row in one
 * transaction. So applying is a **local** operation that completes at disk speed; the server
 * outcome belongs to the sync engine, drained in the background and retried on reconnect.
 *
 * This is the only place that knows which repository serves which action, which is what keeps
 * [actionsFor] pure and stops the screen growing a dependency per field.
 *
 * Internal because [BulkAction] is: the planner and this applier are two halves of one mechanism
 * inside `:app:sharedLogic`, and neither belongs on the export surface.
 */
internal class BulkEditApplier(
    private val bookEditRepository: BookEditRepository,
    private val tagRepository: TagRepository,
    private val moodRepository: MoodRepository,
) {
    /**
     * Applies [actions] to [bookId], stopping at the first failure.
     *
     * Stops rather than continuing because a failure here is local — a DB write, or no signed-in
     * user — and the next call would almost certainly fail the same way. Actions already applied
     * are already committed and stand; this is not a transaction and deliberately so, since forty
     * books are forty independent intents.
     */
    suspend fun apply(
        bookId: BookId,
        actions: List<BulkAction>,
    ): AppResult<Unit> {
        for (action in actions) {
            val result = applyOne(bookId, action)
            if (result is AppResult.Failure) return result
        }
        return AppResult.Success(Unit)
    }

    private suspend fun applyOne(
        bookId: BookId,
        action: BulkAction,
    ): AppResult<Unit> =
        when (action) {
            is BulkAction.Mutate -> applyMutation(bookId, action.mutation)

            // Both take a display NAME and slugify it server-side; a slug passed here would become
            // the created tag's or mood's own display name.
            is BulkAction.AddTag -> tagRepository.addTagToBook(bookId.value, action.name).toUnit()

            is BulkAction.AddMood -> moodRepository.addMoodToBook(bookId.value, action.name).toUnit()
        }

    // NOTE ON COALESCING. `OfflineEditor.edit` takes a `coalesce` flag that drops queued ops
    // matching the same channel, entity and kind. It must stay OFF here: a bulk edit issuing
    // SetSeries and SetGenres for one book produces two ops that are both (Books, bookId, Update) —
    // the channel and kind are the same for every book mutation — and coalescing would silently
    // drop one and lose half the edit. The flag is not passed from here: `BookEditRepositoryImpl`'s
    // private `edit()` omits it, so `OfflineEditor.edit`'s `coalesce = false` default applies. So
    // nothing to do, but do not "optimise" it on later.
    private suspend fun applyMutation(
        bookId: BookId,
        mutation: BookMutation,
    ): AppResult<Unit> =
        when (mutation) {
            is BookMutation.Update -> {
                bookEditRepository.updateBook(bookId, mutation.patch)
            }

            is BookMutation.SetSeries -> {
                bookEditRepository.setBookSeries(bookId, mutation.series)
            }

            is BookMutation.SetContributors -> {
                bookEditRepository.setBookContributors(bookId, mutation.contributors)
            }

            is BookMutation.SetGenres -> {
                bookEditRepository.setBookGenres(bookId, mutation.genres)
            }

            // actionsFor never produces these; a bulk edit cannot express chapters, tier labels,
            // collections or cover deletion. Refusing loudly beats silently doing nothing.
            //
            // Enumerated rather than left to `else`, because `else` over a sealed type buys silence:
            // a ninth BookMutation would compile straight through this branch and be refused at
            // runtime by a bulk edit that had never heard of it. Named, the compiler reports it here
            // — the same property `actionsFor` relies on to stay honest as fields are added.
            is BookMutation.SetChapters,
            is BookMutation.SetTierLabels,
            is BookMutation.SetCollections,
            BookMutation.DeleteCover,
            -> {
                error("Bulk edit cannot apply $mutation")
            }
        }
}

/** Drops a payload the caller has no use for, keeping the failure. */
private fun <T> AppResult<T>.toUnit(): AppResult<Unit> =
    when (this) {
        is AppResult.Success -> AppResult.Success(Unit)
        is AppResult.Failure -> this
    }
