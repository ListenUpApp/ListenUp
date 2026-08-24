package com.calypsan.listenup.server.sidecar

import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.ChapterSource
import com.calypsan.listenup.server.io.hashBytesSha256

/** Bucket width (ms) chapter durations are rounded to before hashing — see [SidecarIdentity.chapterFingerprint]. */
private const val FINGERPRINT_DURATION_BUCKET_MS = 5_000L

/**
 * Pure projection from a [BookSyncPayload] aggregate to a [ListenUpSidecar] — no filesystem,
 * no database. [SidecarWriter] fetches the aggregate (and resolves the on-disk target path)
 * and calls [assemble] to build the bytes it writes.
 *
 * Tags don't ride on the [BookSyncPayload] aggregate (they live in their own junction,
 * resolved by tag id), so the caller resolves the book's tag *names* and passes them in —
 * the projection stays pure while the sidecar still carries the full curation surface.
 * They're emitted sorted so identical curation always produces byte-identical JSON (the
 * round-trip hash discriminator depends on that stability).
 */
class SidecarAssembler {
    /** Builds the [ListenUpSidecar] snapshot for [book], with the tag [tagNames] resolved by the caller. */
    fun assemble(
        book: BookSyncPayload,
        tagNames: List<String> = emptyList(),
    ): ListenUpSidecar =
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
                    series =
                        book.series.map {
                            SidecarSeriesEntry(name = it.name, sequence = it.sequence?.let(::sequenceLabel))
                        },
                    genres = book.genres.map { it.name },
                    tags = tagNames.sorted(),
                ),
            fieldProvenance = curatedProvenance(book),
            chapters = userChaptersOrNull(book),
        )

    /**
     * Renders a stored series position back into the free-form label the disk format speaks:
     * `1.0` writes as `"1"`, `1.5` stays `"1.5"`. A whole number printed as a `Double` is `"1.0"`,
     * and nobody writes "Mistborn #1.0" into a file they may hand-edit. Both spellings read back
     * to the same number through `parseSeriesSequence`, so the round trip is lossless.
     */
    private fun sequenceLabel(sequence: Double): String =
        if (sequence % 1.0 == 0.0) sequence.toLong().toString() else sequence.toString()

    /**
     * The book's provenance map projected onto the disk format: [com.calypsan.listenup.api.metadata.BookField]
     * names as keys, and **only entries above the scan tier**.
     *
     * A tier-0 entry says which file won a field during one scan pass; the next scan re-derives it
     * from the files, so writing it back would be bytes that restore nothing. Enrichment and user
     * entries are exactly the ones a rescan must not silently overwrite — those are what the sidecar
     * exists to carry. Emitted in [com.calypsan.listenup.api.metadata.BookField] declaration order so
     * identical curation always produces byte-identical JSON (the round-trip hash depends on it).
     */
    private fun curatedProvenance(book: BookSyncPayload): Map<String, FieldProvenance> =
        BookField.entries
            .mapNotNull { field -> book.fieldProvenance[field]?.takeIf { it.tier > 0 }?.let { field.name to it } }
            .toMap()

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
