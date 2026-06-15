package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.getOrElse
import com.calypsan.listenup.api.sync.BookMoodSyncPayload
import com.calypsan.listenup.api.sync.Mood
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.sync.BookMoodRepository
import com.calypsan.listenup.server.sync.MoodRepository
import com.calypsan.listenup.server.sync.MoodSlug
import java.util.UUID
import kotlin.time.Clock

/**
 * Scan/enrich-time writer for the `book_moods` junction.
 *
 * Persists a book's mood names (the affective axis, e.g. `"Feel-Good"`, `"Tense"`)
 * onto the book, auto-creating [Mood] catalog rows for names that don't yet exist.
 * Mirrors the find-or-create-then-link path that
 * [com.calypsan.listenup.server.api.MoodServiceImpl.addMoodToBook] uses — minus the
 * per-user permission gate: the book `upsert` (plus the sync firehose) propagates the
 * change.
 *
 * **Add-only.** This writer never wipes the book's existing `book_moods`. A
 * manually-curated mood therefore survives every re-apply — only fresh links are
 * added. A tombstoned link is revived via the junction's `upsert` (which clears
 * `deletedAt`).
 *
 * Inputs are case-insensitive-deduped (`.trim().lowercase()`) so writing
 * `["Feel-Good", "feel-good"]` yields a single junction row. Blanks are skipped.
 */
internal class BookMoodWriter(
    private val clock: Clock,
    private val moodRepository: MoodRepository,
    private val bookMoodRepository: BookMoodRepository,
) {
    /**
     * Links every mood name in [rawMoods] to [bookId], find-or-creating each mood
     * by its canonical slug. Add-only: existing links are left intact.
     *
     * Both [MoodRepository.upsert] and [BookMoodRepository.upsert] open their own
     * substrate transactions (revision bump + change-bus publication), so this
     * method does NOT need to be called inside a `suspendTransaction { }` block.
     */
    suspend fun writeMoods(
        bookId: BookId,
        rawMoods: List<String>,
    ) {
        for (raw in rawMoods.distinctBy { it.trim().lowercase() }) {
            if (raw.isBlank()) continue
            linkMood(bookId, raw)
        }
    }

    /**
     * Resolves a single raw mood [name] to a live [Mood] (creating it if absent) and
     * links [bookId] to it. A blank-after-normalize name is skipped; a lost
     * create race re-resolves by slug so the link still lands.
     */
    private suspend fun linkMood(
        bookId: BookId,
        name: String,
    ) {
        val slug = MoodSlug.normalize(name).getOrElse { return }

        val mood =
            moodRepository.findBySlug(slug)
                ?: createMood(name, slug)
                ?: return

        val now = clock.now().toEpochMilliseconds()
        bookMoodRepository.upsert(
            BookMoodSyncPayload(
                bookId = bookId.value,
                moodId = mood.id,
                createdAt = now,
                revision = 0L,
                deletedAt = null,
            ),
        )
    }

    /**
     * Creates a flat live [Mood] for [name]/[slug] via the substrate `upsert`.
     * On a lost create race the slug is re-resolved so the caller still receives a
     * usable mood; returns null only if creation failed and no row materialized.
     */
    private suspend fun createMood(
        name: String,
        slug: String,
    ): Mood? {
        val newMood =
            Mood(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                slug = slug,
                revision = 0L,
                updatedAt = clock.now().toEpochMilliseconds(),
            )
        return when (val result = moodRepository.upsert(newMood)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> moodRepository.findBySlug(slug)
        }
    }
}
