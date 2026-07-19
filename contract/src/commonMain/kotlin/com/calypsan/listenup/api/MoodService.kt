package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.FacetStats
import com.calypsan.listenup.api.dto.MoodSummary
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.sync.Mood
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.MoodId
import kotlinx.rpc.annotations.Rpc

/**
 * RPC contract for mood lifecycle management and observation.
 *
 * Moods are server-wide (cross-user) entities — the affective axis of a book
 * ("Feel-Good", "Tense", "Scary"), independent of genre and tag. Curators apply
 * them to books. Each mood has a unique slug derived from its display name at
 * creation time; the slug is the stable URL identity — it never changes on rename.
 *
 * Two surface categories:
 * - **Observation** — [listMoods], [getMoodBySlug], [listBooksForMood],
 *   [listMoodsForBook] are safe to call repeatedly; they read state only.
 * - **Mutation** — [addMoodToBook], [removeMoodFromBook], [renameMood],
 *   [deleteMood] mutate server state and should be called once per user intent.
 *
 * The `book_moods` junction is a global cross-user association (curator model).
 * Per-user ACL enforcement is deferred to the Multi-user phase.
 */
@Rpc
interface MoodService {
    // ── Observation ──────────────────────────────────────────────────────────

    /**
     * Returns all non-deleted moods ordered by book count descending, then name
     * ascending, each annotated with its live [MoodSummary.bookCount].
     */
    suspend fun listMoods(): AppResult<List<MoodSummary>>

    /**
     * Returns the mood with the given [slug], or `null` inside [AppResult.Success]
     * when no non-deleted mood with that slug exists.
     *
     * Slug lookups are case-insensitive by construction because slugs are
     * normalized to lowercase at creation time.
     */
    suspend fun getMoodBySlug(slug: String): AppResult<MoodSummary?>

    /**
     * Returns up to [limit] book IDs tagged with [moodId], excluding any books
     * whose junction row has been soft-deleted. [limit] is clamped server-side
     * to `1..1000`. Default 100.
     */
    suspend fun listBooksForMood(
        moodId: MoodId,
        limit: Int = 100,
    ): AppResult<List<BookId>>

    /**
     * Returns all moods currently applied to the book identified by [bookId],
     * excluding any junction rows that have been soft-deleted.
     *
     * Returns an empty list when the book exists but has no moods. Returns
     * [com.calypsan.listenup.api.error.MoodError.BookNotFound] when no book
     * with the given id exists on the server.
     */
    suspend fun listMoodsForBook(bookId: BookId): AppResult<List<Mood>>

    /** Aggregate book count + total length for [moodId] over live books. */
    suspend fun getMoodStats(moodId: MoodId): AppResult<FacetStats>

    // ── Mutation ─────────────────────────────────────────────────────────────

    /**
     * Applies a mood with display name [name] to the book identified by [bookId]
     * and returns the mood aggregate.
     *
     * Find-or-create semantics: if a non-deleted mood whose slug matches
     * `MoodSlug.normalize(name)` already exists, that mood is reused and its
     * display name is NOT mutated. If no matching mood exists, a new one is
     * created. The junction row is upserted (re-adding a previously removed mood
     * clears its `deleted_at` and bumps the revision).
     *
     * Failures:
     * - [com.calypsan.listenup.api.error.MoodError.InvalidName] when [name] is
     *   blank or normalizes to an empty slug.
     * - [com.calypsan.listenup.api.error.MoodError.NameTooLong] when [name]
     *   exceeds 64 characters.
     * - [com.calypsan.listenup.api.error.MoodError.BookNotFound] when no book
     *   with the given id exists.
     */
    suspend fun addMoodToBook(
        bookId: BookId,
        name: String,
    ): AppResult<Mood>

    /**
     * Removes the association between [bookId] and [moodId] by soft-deleting the
     * `book_moods` junction row. Idempotent: calling this on a junction row that
     * is already soft-deleted returns [AppResult.Success] without further writes.
     *
     * Returns [com.calypsan.listenup.api.error.MoodError.BookNotFound] when no
     * book with [bookId] exists. Returns [com.calypsan.listenup.api.error.MoodError.NotFound]
     * when no mood with [moodId] exists.
     */
    suspend fun removeMoodFromBook(
        bookId: BookId,
        moodId: MoodId,
    ): AppResult<Unit>

    /**
     * Updates the display name of the mood identified by [moodId] to [newName]
     * and returns the updated [Mood] aggregate.
     *
     * Rename semantics: only the display [Mood.name] changes — [Mood.slug] is
     * intentionally preserved so that existing URLs and client-side slug lookups
     * remain valid.
     *
     * Failures:
     * - [com.calypsan.listenup.api.error.MoodError.NotFound] when no mood with
     *   the given id exists.
     * - [com.calypsan.listenup.api.error.MoodError.InvalidName] when [newName]
     *   is blank or normalizes to an empty slug.
     * - [com.calypsan.listenup.api.error.MoodError.NameTooLong] when [newName]
     *   exceeds 64 characters.
     */
    suspend fun renameMood(
        moodId: MoodId,
        newName: String,
    ): AppResult<Mood>

    /**
     * Deletes the mood identified by [moodId] and cascade-soft-deletes all of its
     * `book_moods` junction rows atomically inside a single transaction.
     *
     * Returns [com.calypsan.listenup.api.error.MoodError.NotFound] when no mood
     * with the given id exists.
     */
    suspend fun deleteMood(moodId: MoodId): AppResult<Unit>
}
