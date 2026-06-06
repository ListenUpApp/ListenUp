@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.calypsan.listenup.server.services

import com.calypsan.listenup.api.dto.ContributorRole
import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookChapterPayload
import com.calypsan.listenup.api.sync.BookContributorPayload
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
 * **Duration caveat.** The Scanner's [AnalyzedBook] carries no per-track
 * duration — `TrackEntry` wraps only a `FileEntry` (path/size/inode), and
 * `codec` is not surfaced anywhere. The single authoritative duration is
 * `embedded.durationMs`, produced by the parser for the *primary* audio file
 * only (spec §3 non-goal: multi-file books parse the first file). So
 * `totalDuration` and the first audio file's `duration` carry that value;
 * non-primary files get `0L`; `codec` is left blank.
 *
 * `cover` is left null — cover hashing is a later task; the substrate-owned
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
        val inode = candidate.files.firstOrNull()?.inode
        val totalDuration = analyzed.embedded?.durationMs ?: 0L
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
            revision = 0L,
            updatedAt = 0L,
            createdAt = 0L,
            deletedAt = null,
        )
    }

    /**
     * Builds the unresolved (blank-id) contributor payloads for [analyzed]. Each raw
     * author/narrator string is split into individual people with roles by
     * [ContributorParser]; identical `(name, role)` pairs are de-duplicated. The caller
     * resolves each name to a real id via [ContributorRepository.resolveOrCreate] before
     * the aggregate write.
     */
    fun buildContributors(analyzed: AnalyzedBook): List<BookContributorPayload> {
        val parsed =
            analyzed.authors.flatMap { ContributorParser.parse(it, ContributorRole.AUTHOR) } +
                analyzed.narrators.flatMap { ContributorParser.parse(it, ContributorRole.NARRATOR) }
        return parsed.distinct().map { contributorPayload(it.name, it.role.apiValue) }
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
     * Builds the audio-file payloads for [analyzed]. Only the primary (first)
     * audio file has an authoritative duration — see the duration caveat on
     * the class doc.
     */
    fun buildAudioFiles(analyzed: AnalyzedBook): List<BookAudioFilePayload> {
        val primaryDuration = analyzed.embedded?.durationMs ?: 0L
        return analyzed.tracks.mapIndexed { index, track ->
            BookAudioFilePayload(
                id = "",
                index = index,
                filename = track.file.name,
                format = track.file.ext,
                codec = "",
                duration = if (index == 0) primaryDuration else 0L,
                size = track.file.size,
            )
        }
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
        role: String,
    ): BookContributorPayload =
        BookContributorPayload(
            id = "",
            name = name,
            sortName = null,
            role = role,
            creditedAs = null,
        )
}
