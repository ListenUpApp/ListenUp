package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.api.result.getOrElse
import com.calypsan.listenup.api.sync.BookTagSyncPayload
import com.calypsan.listenup.api.sync.Tag
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.sync.BookTagRepository
import com.calypsan.listenup.server.sync.TagRepository
import com.calypsan.listenup.server.sync.TagSlug
import kotlin.uuid.Uuid
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
     * Reconciles [bookId]'s `book_tags` to exactly the tags in [rawTags] (replace) — the
     * match-apply path: find-or-creates each by slug, soft-deletes links no longer wanted, adds
     * new ones. Unlike [writeScanTags] (add-only, scanner), a re-match swaps the old set and a
     * deselected tag is removed; an empty [rawTags] removes all of the book's tags (explicit
     * "none" from the review).
     *
     * Inputs are case-insensitive-deduped and blank-skipped, matching [writeScanTags]. Both
     * [BookTagRepository.softDelete] and [BookTagRepository.upsert] open their own substrate
     * transactions, so this method does NOT need a surrounding `suspendTransaction { }`.
     */
    suspend fun setBookTags(
        bookId: BookId,
        rawTags: List<String>,
    ) {
        val targetTagIds =
            rawTags
                .distinctBy { it.trim().lowercase() }
                .filterNot { it.isBlank() }
                .mapNotNull { resolveTagId(it) }
                .toSet()
        val currentTagIds =
            bookTagRepository
                .findAllForBook(bookId.value)
                .filter { it.deletedAt == null }
                .map { it.tagId }
                .toSet()
        val now = clock.now().toEpochMilliseconds()
        for (tagId in currentTagIds - targetTagIds) {
            bookTagRepository.softDelete(bookId.value, tagId, clientOpId = null)
        }
        for (tagId in targetTagIds - currentTagIds) {
            bookTagRepository.upsert(
                BookTagSyncPayload(
                    id = Uuid.random().toString(),
                    bookId = bookId.value,
                    tagId = tagId,
                    createdAt = now,
                    revision = 0L,
                    deletedAt = null,
                ),
            )
        }
    }

    /**
     * Resolves a single raw tag [name] to a live tag id, creating the catalog row if absent.
     * Returns null when [name] normalizes to a blank slug or no row can be materialized.
     */
    private suspend fun resolveTagId(name: String): String? {
        val slug = TagSlug.normalize(name).getOrElse { return null }
        return (tagRepository.findBySlug(slug) ?: createTag(name, slug))?.id
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
        val tagId = resolveTagId(name) ?: return

        val now = clock.now().toEpochMilliseconds()
        bookTagRepository.upsert(
            BookTagSyncPayload(
                id = Uuid.random().toString(),
                bookId = bookId.value,
                tagId = tagId,
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
                id = Uuid.random().toString(),
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
