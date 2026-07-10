package com.calypsan.listenup.server.sidecar

import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ChapterSource
import com.calypsan.listenup.api.sync.UserEditedField
import com.calypsan.listenup.server.io.hashBytesSha256

/** Bucket width (ms) chapter durations are rounded to before hashing — see [SidecarIdentity.chapterFingerprint]. */
private const val FINGERPRINT_DURATION_BUCKET_MS = 5_000L

/**
 * Pure projection from a [BookSyncPayload] aggregate to a [ListenUpSidecar] — no filesystem,
 * no database. [SidecarWriter] fetches the aggregate (and resolves the on-disk target path)
 * and calls [assemble] to build the bytes it writes.
 *
 * Tags are deliberately NOT populated in this phase: they live in a separate junction
 * (`BookTagRepository`) resolved by tag id, not by name, on the [BookSyncPayload] aggregate,
 * and are outside the user-edit-protection model this phase restores. The schema field exists
 * for forward compatibility (a human can hand-author it) but the assembler always emits an
 * empty list here.
 */
class SidecarAssembler {
    /** Builds the [ListenUpSidecar] snapshot for [book]. */
    fun assemble(book: BookSyncPayload): ListenUpSidecar =
        ListenUpSidecar(
            identity =
                SidecarIdentity(
                    asin = book.asin,
                    chapterFingerprint = chapterFingerprint(book.chapters),
                    titleAuthor = titleAuthorKey(book),
                ),
            metadata =
                SidecarCuratedMetadata(
                    title = book.title,
                    subtitle = book.subtitle,
                    description = book.description,
                    contributors = book.contributors.map { SidecarContributor(name = it.name, role = it.role) },
                    series = book.series.map { SidecarSeriesEntry(name = it.name, sequence = it.sequence) },
                    genres = book.genres.map { it.name },
                ),
            userEditedFields = UserEditedField.entries.filter { it in book.userEditedFields }.map { it.name },
            chapters = userChaptersOrNull(book),
        )

    private fun userChaptersOrNull(book: BookSyncPayload): SidecarChapters? {
        if (book.chapterSource != ChapterSource.USER) return null
        return SidecarChapters(
            source = "USER",
            entries =
                book.chapters.sortedBy { it.startTime }.map {
                    SidecarChapter(
                        title = it.title,
                        startMs = it.startTime,
                    )
                },
        )
    }

    /**
     * The `"<title> / <authors>"` fuzzy-match key (Integration Foundations §7.4's last-resort
     * fallback). Falls back to the bare title when the book has no `"author"`-role contributor.
     */
    private fun titleAuthorKey(book: BookSyncPayload): String {
        val authors = book.contributors.filter { it.role.equals("author", ignoreCase = true) }.map { it.name }
        return if (authors.isEmpty()) book.title else "${book.title} / ${authors.joinToString(", ")}"
    }

    /**
     * The canonical v1 chapter-snapshot fingerprint — see [SidecarIdentity.chapterFingerprint]'s
     * KDoc for the formula. `null` when the book has no chapters at all.
     */
    private fun chapterFingerprint(chapters: List<BookChapterPayload>): String? {
        if (chapters.isEmpty()) return null
        val key =
            chapters.joinToString("|") { chapter ->
                "${chapter.title.trim().lowercase()}:${chapter.duration / FINGERPRINT_DURATION_BUCKET_MS}"
            }
        return hashBytesSha256(key.encodeToByteArray())
    }
}
