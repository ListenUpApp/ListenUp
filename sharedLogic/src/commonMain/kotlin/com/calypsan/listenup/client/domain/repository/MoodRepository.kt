@file:MustUseReturnValues

package com.calypsan.listenup.client.domain.repository

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.client.domain.model.Mood
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for mood lifecycle and observation.
 *
 * Moods are global (cross-user) affective descriptors applied to books by curators
 * ("Feel-Good", "Tense", "Scary"). Each mood has a URL-safe [Mood.slug] derived from
 * its display name at creation time; the slug is the stable identity — it never changes
 * on rename.
 *
 * **Observation methods** read from Room (offline-first, reactive). **Mutation methods**
 * delegate to the [com.calypsan.listenup.api.MoodService] RPC; the SSE sync engine
 * propagates the server-committed change back into Room, which triggers reactive UI updates.
 *
 * Mutation methods are the primary write path — no optimistic Room writes. The sync
 * engine is the single path from server state to Room.
 *
 * Mirrors [TagRepository].
 */
interface MoodRepository {
    // ── Observation (Room-backed, offline-first) ──────────────────────────────

    /**
     * Observe all non-tombstoned moods ordered by name ascending.
     *
     * @return Flow emitting the current list of live moods; re-emits on any mood change.
     */
    fun observeAllMoods(): Flow<List<Mood>>

    /**
     * Observe all non-tombstoned moods currently applied to the given book.
     *
     * Excludes both tombstoned moods and tombstoned junction rows so removals
     * surface reactively without an explicit refresh.
     *
     * @param bookId The string book ID.
     * @return Flow emitting the live mood list for the book; re-emits on any change.
     */
    fun observeMoodsForBook(bookId: String): Flow<List<Mood>>

    // ── Mutation (RPC-backed) ─────────────────────────────────────────────────

    /**
     * Apply the mood with display name [name] to [bookId].
     *
     * Find-or-create semantics: if a non-deleted mood whose slug matches the
     * normalized [name] already exists it is reused; otherwise a new mood is
     * created. The junction row is upserted (re-adding a previously removed mood
     * clears its `deleted_at` and bumps the revision).
     *
     * The server emits SSE events for the new or updated mood and junction row so
     * connected clients' Room databases update reactively via the sync engine.
     *
     * @param bookId The raw book ID string.
     * @param name The mood display name (will be slugified server-side).
     * @return [AppResult.Success] containing the mood aggregate, or typed failure.
     */
    suspend fun addMoodToBook(
        bookId: String,
        name: String,
    ): AppResult<Mood>

    /**
     * Remove the association between [bookId] and [moodId] by soft-deleting the junction row.
     *
     * Idempotent: calling this on an already-removed junction row returns Success.
     *
     * @param bookId The raw book ID string.
     * @param moodId The mood ID to disassociate.
     * @return [AppResult.Success] with Unit on success, or typed failure.
     */
    suspend fun removeMoodFromBook(
        bookId: String,
        moodId: String,
    ): AppResult<Unit>
}