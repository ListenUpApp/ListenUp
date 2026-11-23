package com.calypsan.listenup.api

import com.calypsan.listenup.api.dto.FacetStats
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
 * - **Observation** — [getMoodStats] is safe to call repeatedly; it reads state only. Clients
 *   read moods and their books from their local mirror.
 * - **Mutation** — [addMoodToBook], [removeMoodFromBook] mutate server state and should be
 *   called once per user intent.
 *
 * The `book_moods` junction is a global cross-user association (curator model) — one shared
 * vocabulary, curated by anyone with `canEdit`. Visibility is a separate axis from curation:
 *
 * - **Mutations** are gated on the per-user `canEdit` flag. ROOT/ADMIN pass implicitly.
 * - **Reads that touch books** are scoped to the caller through `BookAccessPolicy`, the single
 *   definition of book visibility. A book the caller cannot reach is never enumerated, and asking
 *   about one directly fails with the same `BookNotFound` an absent book produces — a denial that
 *   said "forbidden" would itself confirm the book exists.
 */
@Rpc
interface MoodService {
    // ── Observation ──────────────────────────────────────────────────────────

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
}
