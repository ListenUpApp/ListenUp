package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.getOrElse
import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.sync.TagSlug
import java.util.UUID
import kotlin.time.Clock

/**
 * Scan-time writer for the `book_tags` junction.
 *
 * Persists the ABS `metadata.json` `tags[]` array (tropes like `"Found Family"`)
 * onto the book, auto-creating [Tag] catalog rows for names that don't yet exist.
 * Mirrors the find-or-create-then-link path that
 * [com.calypsan.listenup.server.api.TagServiceImpl.addTagToBook] uses — minus the
 * per-user permission gate and FTS reindex, which are interactive-edit concerns:
 * the scan's book `upsert` (plus the sync firehose) propagates the change.
 *
 * **Add-only on rescan.** Unlike [BookGenreWriter], this writer never wipes the
 * book's existing `book_tags`. A manually-curated tag therefore survives every
 * rescan — only fresh links are added. A tombstoned link is revived via the
 * junction's `upsert` (which clears `deletedAt`).
 *
 * Inputs are case-insensitive-deduped (`.trim().lowercase()`) so scanning
 * `["Found Family", "found family"]` yields a single junction row. Blanks are skipped.
 */
internal class BookTagWriter(
    private val clock: Clock,
    private val tagRepository: TagRepository,
    private val bookTagRepository: BookTagRepository,
) {
    /**
     * Links every tag name in [rawTags] to [bookId], find-or-creating each tag
     * by its canonical slug. Add-only: existing links are left intact.
     *
     * Both [TagRepository.upsert] and [BookTagRepository.upsert] open their own
     * substrate transactions (revision bump + change-bus publication), so this
     * method does NOT need to be called inside a `suspendTransaction { }` block —
     * unlike [BookGenreWriter.processGenreStrings].
     */
    suspend fun writeScanTags(
        bookId: BookId,
        rawTags: List<String>,
    ) {
        for (raw in rawTags.distinctBy { it.trim().lowercase() }) {
            if (raw.isBlank()) continue
            linkTag(bookId, raw)
        }
    }

    /**
     * Resolves a single raw tag [name] to a live [Tag] (creating it if absent) and
     * links [bookId] to it. A blank-after-normalize name is skipped; a lost
     * create race re-resolves by slug so the link still lands.
     */
    private suspend fun linkTag(
        bookId: BookId,
        name: String,
    ) {
        val slug = TagSlug.normalize(name).getOrElse { return }

        val tag =
            tagRepository.findBySlug(slug)
                ?: createTag(name, slug)
                ?: return

        val now = clock.now().toEpochMilliseconds()
        bookTagRepository.upsert(
            BookTagSyncPayload(
                bookId = bookId.value,
                tagId = tag.id,
                createdAt = now,
                revision = 0L,
                deletedAt = null,
            ),
        )
    }

    /**
     * Creates a flat live [Tag] for [name]/[slug] via the substrate `upsert`.
     * On a lost create race the slug is re-resolved so the caller still receives a
     * usable tag; returns null only if creation failed and no row materialized.
     */
    private suspend fun createTag(
        name: String,
        slug: String,
    ): Tag? {
        val newTag =
            Tag(
                id = UUID.randomUUID().toString(),
                name = name.trim(),
                slug = slug,
                revision = 0L,
                updatedAt = clock.now().toEpochMilliseconds(),
            )
        return when (val result = tagRepository.upsert(newTag)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> tagRepository.findBySlug(slug)
        }
    }
}
