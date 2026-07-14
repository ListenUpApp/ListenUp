@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
import com.calypsan.listenup.api.sync.BookDocumentPayload
import com.calypsan.listenup.api.sync.BookSeriesPayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.scanner.pipeline.ContributorParser
import com.calypsan.listenup.server.scanner.pipeline.SortKeys
import kotlin.time.Clock

/**
 * Pure-function mapper from the scanner's [AnalyzedBook] to the wire-format
 * [BookSyncPayload] persisted by [BookRepository.upsertFromAnalyzed].
 *
 * The mapping flattens the scanner's resolved view onto the wire shape:
 *  - `authors` + `narrators` become contributor rows via [buildContributors],
 *    which splits each raw string into individual people and parses their roles
 *    through [ContributorParser] (an explicit ` - Role` suffix overrides the
 *    author/narrator default). The caller resolves each name through
 *    [ContributorRepository] before passing the result to [toBookSyncPayload];
 *    this class never touches the database.
 *  - `series` entries map one-to-one to series memberships via [buildSeries];
 *    the caller similarly resolves names through [SeriesRepository].
 *  - `tracks` map to audio files; `filename` / `format` / `size` come from the
 *    track's [com.calypsan.listenup.api.dto.scanner.FileEntry].
 *  - `chapters` map to chapter rows (`duration = endMs - startMs`).
 *
 * **Duration.** Each [com.calypsan.listenup.api.dto.scanner.TrackEntry] carries its own
 * `durationMs` (parsed per-track for multi-file books by the Analyzer). Every
 * `book_audio_files` row gets its track's duration, and `totalDuration` is their sum.
 * Single-file books leave `TrackEntry.durationMs` null, so both the one file's duration
 * and `totalDuration` fall back to the book-level `embedded.durationMs`.
 *
 * **Audio stream.** The primary (first) file's `codec`/`codecProfile`/`spatial`/
 * `bitrate`/`sampleRate`/`channels` come from `embedded.audioStream`; secondary
 * files leave them null (multi-file per-track audio metadata is a non-goal).
 *
 * `cover` is left null — cover hashing is handled separately; the substrate-owned
 * `revision` / `updatedAt` / `createdAt` placeholders are overwritten by
 * `upsert`.
 *
 * Extracted from [BookRepository] so the repository file stays focused on
 * aggregate persistence; this class owns the pure scanner-to-wire
 * transformation and is exercised by [BookRepository] for the
 * resolution+write path and by `AnalyzedBookMapperTest` for the shape.
 */
class AnalyzedBookMapper(
    private val clock: Clock = Clock.System,
) {
    /**
     * Builds a [BookSyncPayload] from [analyzed] under the supplied identity
     * triple ([bookId] / [libraryId] / [folderId]). The caller supplies the
     * already-resolved contributor and series lists — those resolutions touch
     * the database and stay in [BookRepository].
     */
    fun toBookSyncPayload(
        bookId: BookId,
        libraryId: LibraryId,
        folderId: FolderId,
        analyzed: AnalyzedBook,
        resolvedContributors: List<BookContributorPayload>,
        resolvedSeries: List<BookSeriesPayload>,
    ): BookSyncPayload {
        val candidate = analyzed.candidate
        val inode = candidate.identityInode()
        // Sum the per-track durations (multi-file books); fall back to the book-level
        // embedded duration for single-file books, where TrackEntry.durationMs is null.
        val totalDuration =
            analyzed.tracks
                .sumOf { it.durationMs ?: 0L }
                .takeIf { it > 0L }
                ?: (analyzed.embedded?.durationMs ?: 0L)
        return BookSyncPayload(
            id = bookId.value,
            libraryId = libraryId,
            folderId = folderId,
            title = analyzed.title,
            sortTitle = SortKeys.titleSort(analyzed.title, analyzed.embedded?.tags?.titleSort),
            subtitle = analyzed.subtitle,
            description = analyzed.description,
            publishYear = analyzed.publishedYear,
            publisher = analyzed.publisher,
            language = analyzed.language,
            isbn = analyzed.isbn,
            asin = analyzed.asin,
            abridged = analyzed.abridged ?: false,
            explicit = analyzed.explicit ?: false,
            hasScanWarning = analyzed.hasScanWarning,
            totalDuration = totalDuration,
            cover = null,
            rootRelPath = candidate.rootRelPath,
            inode = inode,
            scannedAt = clock.now().toEpochMilliseconds(),
            contributors = resolvedContributors,
            series = resolvedSeries,
            audioFiles = buildAudioFiles(analyzed),
            chapters = buildChapters(analyzed),
            documents = buildDocuments(analyzed),
            // Carry the scanner's per-field scan-tier provenance onto the wire payload; the merge in
            // BookRepository folds it into the persisted max-tier union.
            fieldProvenance = analyzed.fieldProvenance,
            revision = 0L,
            updatedAt = 0L,
            createdAt = 0L,
            deletedAt = null,
        )
    }

    /**
     * Builds the unresolved (blank-id) contributor payloads for [analyzed]. Each raw
     * author/narrator string is split into individual people with roles by
     * [ContributorParser]; identical payloads are de-duplicated by struct equality. Because
     * `sortName` is derived deterministically from `(name, embeddedSort)`, identical
     * `(name, role)` inputs still collapse to a single entry in practice. The caller
     * resolves each name to a real id via [ContributorRepository.resolveOrCreate] before
     * the aggregate write.
     *
     * `sortName` is populated from the embedded `authorsSort` tag (TSOP/soar) when its
     * per-person count matches the parsed author count; otherwise [SortKeys.sortName]
     * derives `"Surname, Given"` from the display name. Narrators always use derivation
     * (there is no narrator-sort tag in common audio formats).
     */
    fun buildContributors(analyzed: AnalyzedBook): List<BookContributorPayload> {
        val authors = analyzed.authors.flatMap { ContributorParser.parse(it, ContributorRole.AUTHOR) }
        val narrators = analyzed.narrators.flatMap { ContributorParser.parse(it, ContributorRole.NARRATOR) }

        // The embedded authors-sort string (TSOP/soar) sorts the same author field; use it as the
        // authoritative sort form only when its person count matches the parsed authors, else
        // derive "Surname, Given" from the display name.
        val authorSorts =
            analyzed.embedded
                ?.tags
                ?.authorsSort
                ?.let { ContributorParser.personNames(it) }
                ?.takeIf { it.size == authors.size }

        val authorPayloads =
            authors.mapIndexed { i, parsed ->
                contributorPayload(parsed.name, parsed.role, authorSorts?.get(i))
            }
        val narratorPayloads =
            narrators.map { parsed -> contributorPayload(parsed.name, parsed.role, embeddedSort = null) }

        return (authorPayloads + narratorPayloads).distinct()
    }

    /**
     * Builds the unresolved (blank-id) series payloads for [analyzed]. The
     * caller resolves each name to a real id via
     * [SeriesRepository.resolveOrCreate] before the aggregate write.
     */
    fun buildSeries(analyzed: AnalyzedBook): List<BookSeriesPayload> =
        analyzed.series.map { entry ->
            BookSeriesPayload(id = "", name = entry.name, sequence = entry.sequence)
        }

    /**
     * Builds the audio-file payloads for [analyzed]. Each track carries its own
     * `durationMs` (multi-file books); single-file books fall back to the primary
     * `embedded.durationMs` — see the duration note on the class doc. The
     * primary file's `codec`/`codecProfile`/`spatial`/
     * `bitrate`/`sampleRate`/`channels` come from `embedded.audioStream`;
     * secondary files leave them null (multi-file per-track metadata is a
     * non-goal).
     */
    fun buildAudioFiles(analyzed: AnalyzedBook): List<BookAudioFilePayload> {
        val primaryDuration = analyzed.embedded?.durationMs ?: 0L
        val primaryStream = analyzed.embedded?.audioStream
        return analyzed.tracks.mapIndexed { index, track ->
            // The scanner parses embedded metadata for the primary (first) file only — a
            // documented non-goal for multi-file per-track metadata — so codec/profile/
            // spatial/bitrate/sampleRate/channels are carried on index 0 and left null elsewhere.
            val stream = if (index == 0) primaryStream else null
            BookAudioFilePayload(
                id = "",
                index = index,
                filename = track.file.name,
                format = track.file.ext,
                codec = stream?.codec ?: "",
                duration = track.durationMs ?: if (index == 0) primaryDuration else 0L,
                size = track.file.size,
                codecProfile = stream?.codecProfile,
                spatial = stream?.spatial,
                bitrate = stream?.bitrate,
                sampleRate = stream?.sampleRate,
                channels = stream?.channels,
            )
        }
    }

    /**
     * Builds the document payloads for [analyzed]. Each [AnalyzedDocument] maps
     * one-to-one to a [BookDocumentPayload]; `id` is left blank for the caller to
     * fill in (mirroring the audio-file convention).
     */
    fun buildDocuments(analyzed: AnalyzedBook): List<BookDocumentPayload> =
        analyzed.documents.mapIndexed { index, doc ->
            BookDocumentPayload(
                id = "",
                index = index,
                filename = doc.relPath,
                format = doc.format,
                size = doc.size,
                hash = doc.hash,
            )
        }

    /** Builds the chapter payloads for [analyzed]; `duration = endMs - startMs`. */
    fun buildChapters(analyzed: AnalyzedBook): List<BookChapterPayload> =
        analyzed.chapters.map { chapter ->
            BookChapterPayload(
                id = "",
                title = chapter.title,
                duration = chapter.endMs - chapter.startMs,
                startTime = chapter.startMs,
            )
        }

    private fun contributorPayload(
        name: String,
        role: ContributorRole,
        embeddedSort: String?,
    ): BookContributorPayload =
        BookContributorPayload(
            id = "",
            name = name,
            sortName = SortKeys.sortName(name, embeddedSort),
            role = role.apiValue,
            creditedAs = null,
        )
}

/**
 * The inode that anchors a book's stable identity across rescans: the first
 * AUDIO file's inode (the first file that both is audio and has a stable inode).
 *
 * Anchoring on audio — rather than `files.first()`, which can be a cover image
 * that name-sorts ahead of the audio — keeps identity aligned with the
 * [com.calypsan.listenup.server.scanner.pipeline.Differ]'s audio-inode move
 * matching. Replacing or adding a non-audio file (a retag writing a fresh cover,
 * a new `00-intro` sorted first) can no longer flip the anchor inode and re-mint
 * the book's UUID, which would strand the user's positions, progress, shelves,
 * and edits on the swept-away old row.
 */
internal fun CandidateBook.identityInode(): Long? =
    files.firstOrNull { it.fileType == FileType.AUDIO && it.inode != null }?.inode
