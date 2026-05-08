package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.BookChapterSource
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.CoverSource
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.MetadataSource
import com.calypsan.listenup.api.dto.scanner.MetadataStatus
import com.calypsan.listenup.api.dto.scanner.SeriesEntry
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.error.AudioMetadataError
import com.calypsan.listenup.api.external.abs.AbsChapter
import com.calypsan.listenup.api.external.abs.AbsMetadata
import com.calypsan.listenup.client.core.AppResult
import com.calypsan.listenup.domain.embeddedmeta.Chapter
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.scanner.inference.AbsTitleParser
import com.calypsan.listenup.server.scanner.inference.FolderShape
import com.calypsan.listenup.server.scanner.inference.ParsedTitle
import com.calypsan.listenup.server.scanner.inference.TrackInference
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.file.Path
import com.calypsan.listenup.domain.embeddedmeta.SeriesEntry as EmbeddedSeriesEntry

private val logger = KotlinLogging.logger {}

/**
 * Stage 3 of the scanner pipeline: turns each [CandidateBook] into an
 * [AnalyzedBook] by combining four signal sources, in increasing precedence:
 *
 *  1. **Folder structure** — bottom-three components map to
 *     `<author>/<series>/<title>`.
 *  2. **Title-folder regex** — strips `[ASIN]`, `{Narrator}`, year prefix,
 *     volume/sequence prefix, optional subtitle. See [AbsTitleParser].
 *  3. **Embedded audio tags** — ID3v2/ID3v1 (MP3), MP4 `ilst` atoms, etc.
 *     Read from the primary audio file (first by stable track order) via
 *     [EmbeddedMetadataParser]. The parser is the only authoritative source
 *     for `durationMs`, `chapters`, and embedded `artwork` bytes — those
 *     fields survive on [AnalyzedBook.embedded] verbatim, while textual
 *     fields participate in the resolved view.
 *  4. **`metadata.json` overlay** — fields in a sidecar override
 *     embedded-, filename-, and folder-derived values when present.
 *
 * Cover-image precedence is its own rule, separate from the textual chain:
 * filesystem `cover.*` → first sibling image → embedded artwork. The
 * filesystem-first order respects user intent — if a `cover.jpg` is on
 * disk, the user put it there for a reason.
 *
 * Per-book failures (unexpected exceptions during analysis) surface as
 * `Result.failure`; the upstream caller decides whether to log-and-continue
 * or abort the scan. `CancellationException` is always re-raised so
 * structured concurrency stays intact. Embedded-metadata parse failures
 * are NOT analysis failures — they surface as
 * [AnalyzedBook.embeddedStatus] and the book is still produced.
 */
internal class Analyzer(
    private val rootPath: Path,
    private val metadataReader: AbsMetadataReader,
    private val embeddedMetadataParser: EmbeddedMetadataParser,
    private val parseSubtitle: Boolean = false,
) {
    fun analyze(candidates: Flow<CandidateBook>): Flow<Result<AnalyzedBook>> =
        flow {
            candidates.collect { c -> emit(analyzeOne(c)) }
        }

    private suspend fun analyzeOne(candidate: CandidateBook): Result<AnalyzedBook> =
        safeRun {
            val shape = FolderShape.parse(candidate.rootRelPath)
            val parsed =
                AbsTitleParser.parse(
                    folder = shape.titleFolder,
                    hasSeriesFolder = shape.seriesFolder != null,
                    parseSubtitle = parseSubtitle,
                )
            val tracks = buildTracks(candidate)
            val primaryAudio = tracks.firstOrNull()?.file
            val (embedded, embeddedStatus) = parseEmbedded(primaryAudio)
            val cover = resolveCover(candidate.files, embedded)
            val metadata = readMetadata(candidate)
            val perTrackMetadata: Map<TrackEntry, EmbeddedAudioMetadata?> =
                if (shouldSynthesizeChapters(metadata, embedded, tracks)) {
                    parseAllTrackDurations(tracks)
                } else {
                    emptyMap()
                }
            compose(candidate, shape, parsed, tracks, cover, embedded, embeddedStatus, metadata, perTrackMetadata)
        }

    private fun buildTracks(candidate: CandidateBook): List<TrackEntry> =
        candidate.files
            .filter { it.fileType == FileType.AUDIO }
            .map { file ->
                val parentFolder =
                    file.relPath
                        .split('/')
                        .dropLast(1)
                        .lastOrNull()
                val info = TrackInference.infer(file.name, parentFolder)
                TrackEntry(
                    file = file,
                    trackNumber = info.trackNumber,
                    discNumber = info.discNumber,
                    trackSource = info.trackSource,
                    discSource = info.discSource,
                )
            }.sortedWith(NATURAL_TRACK_ORDER)

    /**
     * Cover precedence: filesystem `cover.*` → first sibling image →
     * embedded artwork. Filesystem-first respects user intent; embedded
     * is the fallback when no filesystem image exists.
     */
    private fun resolveCover(
        files: List<FileEntry>,
        embedded: EmbeddedAudioMetadata?,
    ): CoverSource? {
        val images = files.filter { it.fileType == FileType.IMAGE }
        val coverByName =
            images.firstOrNull {
                it.name.substringBeforeLast('.').equals("cover", ignoreCase = true)
            }
        val filesystemFile = coverByName ?: images.firstOrNull()
        if (filesystemFile != null) return CoverSource.Filesystem(filesystemFile)
        return embedded?.artwork?.let(CoverSource::Embedded)
    }

    /**
     * Invokes [embeddedMetadataParser] on the candidate's primary audio
     * file and folds the typed [AppResult] into a parser outcome paired
     * with the raw [EmbeddedAudioMetadata].
     *
     * Returns `(null, null)` when the candidate has no audio file or when
     * the file is too small to even sniff format magic bytes — the latter
     * is a defensive guard for cloud-stub placeholder files.
     *
     * [AudioMetadataError.UnsupportedFormat] surfaces as
     * [MetadataStatus.UnsupportedFormat] (carries the format for scan
     * summaries); every other failure surfaces as
     * [MetadataStatus.ParseError] so the typed error survives to the
     * client.
     */
    private suspend fun parseEmbedded(primaryAudio: FileEntry?): Pair<EmbeddedAudioMetadata?, MetadataStatus?> {
        if (primaryAudio == null) return null to null
        if (primaryAudio.size < AudioFormatDetector.MIN_HEADER_BYTES) {
            return null to MetadataStatus.UnsupportedFormat(format = null)
        }
        val absolutePath = rootPath.resolve(primaryAudio.relPath)
        val ioPath = kotlinx.io.files.Path(absolutePath.toString())
        return when (val result = embeddedMetadataParser.parse(ioPath)) {
            is AppResult.Success -> {
                result.data to MetadataStatus.Available
            }

            is AppResult.Failure -> {
                logger.warn {
                    "embeddedmeta parse failed path=$absolutePath err=${result.error.code} corr=${result.error.correlationId}"
                }
                val status =
                    when (val err = result.error) {
                        is AudioMetadataError.UnsupportedFormat -> MetadataStatus.UnsupportedFormat(err.format)
                        is AudioMetadataError -> MetadataStatus.ParseError(err)
                        else -> MetadataStatus.ParseError(AudioMetadataError.IoError(absolutePath.toString(), err.code))
                    }
                null to status
            }
        }
    }

    /**
     * Per-track parse for chapter synthesis. Mirrors [parseEmbedded] but
     * discards the [MetadataStatus] distinctions — synthesis only cares
     * whether a duration came back.
     *
     * Returns a map keyed by [TrackEntry]. A `null` value means the track's
     * parse failed (file too small, unsupported format, IO error); the
     * caller (synthesis) substitutes `0L` for [durationMs] in that case
     * and the affected chapter is zero-length.
     *
     * Called only from the synthesis-eligible branch in [analyzeOne], so
     * single-file books and books with higher-precedence chapter sources
     * pay nothing.
     */
    private suspend fun parseAllTrackDurations(tracks: List<TrackEntry>): Map<TrackEntry, EmbeddedAudioMetadata?> {
        val result = LinkedHashMap<TrackEntry, EmbeddedAudioMetadata?>(tracks.size)
        for (track in tracks) {
            result[track] = parseTrackForDuration(track.file)
        }
        return result
    }

    private suspend fun parseTrackForDuration(file: FileEntry): EmbeddedAudioMetadata? {
        if (file.size < AudioFormatDetector.MIN_HEADER_BYTES) return null
        val absolutePath = rootPath.resolve(file.relPath)
        val ioPath = kotlinx.io.files.Path(absolutePath.toString())
        return when (val result = embeddedMetadataParser.parse(ioPath)) {
            is AppResult.Success -> {
                result.data
            }

            is AppResult.Failure -> {
                logger.warn {
                    "synthesis per-track parse failed " +
                        "path=$absolutePath err=${result.error.code} " +
                        "corr=${result.error.correlationId}"
                }
                null
            }
        }
    }

    private suspend fun readMetadata(candidate: CandidateBook): AbsMetadata? {
        val sidecar =
            candidate.files.firstOrNull {
                it.fileType == FileType.METADATA && it.name.equals("metadata.json", ignoreCase = true)
            } ?: return null
        return metadataReader.read(rootPath.resolve(sidecar.relPath))
    }

    @Suppress("LongParameterList") // Composing the merged view honestly takes every source.
    private fun compose(
        candidate: CandidateBook,
        shape: FolderShape,
        parsed: ParsedTitle,
        tracks: List<TrackEntry>,
        cover: CoverSource?,
        embedded: EmbeddedAudioMetadata?,
        embeddedStatus: MetadataStatus?,
        metadata: AbsMetadata?,
        perTrackMetadata: Map<TrackEntry, EmbeddedAudioMetadata?>,
    ): AnalyzedBook {
        val title = pickTitle(candidate, shape, parsed, embedded, metadata)
        val (resolvedChapters, chaptersSource) = pickChapters(embedded, metadata, tracks, perTrackMetadata, title)
        return AnalyzedBook(
            candidate = candidate,
            title = title,
            subtitle = metadata?.subtitle ?: embedded?.tags?.subtitle ?: parsed.subtitle,
            authors = pickAuthors(shape, embedded, metadata),
            narrators = pickNarrators(parsed, embedded, metadata),
            series = pickSeries(shape, parsed, embedded, metadata),
            publishedYear = metadata?.publishedYear ?: embedded?.tags?.publishedYear ?: parsed.publishedYear,
            asin = metadata?.asin ?: embedded?.tags?.asin ?: parsed.asin,
            isbn = metadata?.isbn ?: embedded?.tags?.isbn,
            description = metadata?.description ?: embedded?.tags?.description,
            publisher = metadata?.publisher ?: embedded?.tags?.publisher,
            language = metadata?.language ?: embedded?.tags?.language,
            genres = pickGenres(embedded, metadata),
            tags = metadata?.tags.orEmpty(),
            abridged = metadata?.abridged,
            explicit = metadata?.explicit,
            cover = cover,
            tracks = tracks,
            chapters = resolvedChapters,
            chaptersSource = chaptersSource,
            embedded = embedded,
            embeddedStatus = embeddedStatus,
            sources = collectSources(shape, parsed, embedded, metadata),
        )
    }

    /**
     * Chapter precedence:
     *   metadata.json (non-empty) → embedded (non-empty) → synthesized (multi-file) → empty.
     *
     * Spec §3 of `2026-05-07-phase-4-multifile-chapter-synthesis-design.md`. The
     * sidecar wins when present so user-curated chapter titles in ABS survive
     * a rescan. Synthesis activates only when no higher source exists AND the
     * book is multi-file.
     *
     * `embedded.chapters` continues to surface verbatim on
     * [AnalyzedBook.embedded] regardless of which won — the resolved view is
     * additive, not destructive.
     */
    private fun pickChapters(
        embedded: EmbeddedAudioMetadata?,
        metadata: AbsMetadata?,
        tracks: List<TrackEntry>,
        perTrackMetadata: Map<TrackEntry, EmbeddedAudioMetadata?>,
        bookTitle: String,
    ): Pair<List<Chapter>, BookChapterSource> {
        val sidecar = metadata?.chapters.orEmpty()
        if (sidecar.isNotEmpty()) {
            return sidecar.toDomainChapters() to BookChapterSource.AbsMetadata
        }
        if (embedded != null && embedded.chapters.isNotEmpty()) {
            return embedded.chapters to BookChapterSource.Embedded(embedded.chaptersSource)
        }
        if (tracks.size >= 2) {
            return synthesizeChapters(tracks, perTrackMetadata, bookTitle) to
                BookChapterSource.SynthesizedFromTracks
        }
        return emptyList<Chapter>() to BookChapterSource.None
    }

    /**
     * Synthesis is eligible when no higher-precedence chapter source exists
     * AND the book is multi-file. Spec §3.
     */
    private fun shouldSynthesizeChapters(
        metadata: AbsMetadata?,
        embedded: EmbeddedAudioMetadata?,
        tracks: List<TrackEntry>,
    ): Boolean {
        if (metadata?.chapters?.isNotEmpty() == true) return false
        if (embedded != null && embedded.chapters.isNotEmpty()) return false
        return tracks.size >= 2
    }

    private fun pickTitle(
        candidate: CandidateBook,
        shape: FolderShape,
        parsed: ParsedTitle,
        embedded: EmbeddedAudioMetadata?,
        metadata: AbsMetadata?,
    ): String =
        metadata?.title?.takeUnless { it.isBlank() }
            ?: embedded?.tags?.title?.takeUnless { it.isBlank() }
            ?: parsed.title.takeUnless { it.isBlank() }
            ?: shape.titleFolder.takeUnless { it.isBlank() }
            ?: candidate.rootRelPath

    private fun pickAuthors(
        shape: FolderShape,
        embedded: EmbeddedAudioMetadata?,
        metadata: AbsMetadata?,
    ): List<String> =
        metadata?.authors?.takeIf { it.isNotEmpty() }
            ?: embedded?.tags?.authors?.takeIf { it.isNotEmpty() }
            ?: shape.authorFolder?.let { listOf(it) }
            ?: emptyList()

    private fun pickNarrators(
        parsed: ParsedTitle,
        embedded: EmbeddedAudioMetadata?,
        metadata: AbsMetadata?,
    ): List<String> =
        metadata?.narrators?.takeIf { it.isNotEmpty() }
            ?: embedded?.tags?.narrators?.takeIf { it.isNotEmpty() }
            ?: parsed.narrators

    private fun pickSeries(
        shape: FolderShape,
        parsed: ParsedTitle,
        embedded: EmbeddedAudioMetadata?,
        metadata: AbsMetadata?,
    ): List<SeriesEntry> {
        val fromMetadata = metadataReader.parseSeriesEntries(metadata?.series.orEmpty())
        if (fromMetadata.isNotEmpty()) return fromMetadata
        val fromEmbedded =
            embedded
                ?.tags
                ?.series
                .orEmpty()
                .map(EmbeddedSeriesEntry::toContract)
        if (fromEmbedded.isNotEmpty()) return fromEmbedded
        return shape.seriesFolder
            ?.let { listOf(SeriesEntry(name = it, sequence = parsed.sequence)) }
            ?: emptyList()
    }

    private fun pickGenres(
        embedded: EmbeddedAudioMetadata?,
        metadata: AbsMetadata?,
    ): List<String> =
        metadata?.genres?.takeIf { it.isNotEmpty() }
            ?: embedded?.tags?.genres.orEmpty()

    private fun collectSources(
        shape: FolderShape,
        parsed: ParsedTitle,
        embedded: EmbeddedAudioMetadata?,
        metadata: AbsMetadata?,
    ): Set<MetadataSource> {
        val sources = mutableSetOf<MetadataSource>()
        if (shape.contributesAnything()) sources += MetadataSource.FOLDER_STRUCTURE
        if (parsed.hasAnyFilenameAnnotation()) sources += MetadataSource.FILENAME
        if (embedded != null) sources += MetadataSource.AUDIO_METATAGS
        if (metadata != null) sources += MetadataSource.ABS_METADATA
        return sources
    }
}

private fun EmbeddedSeriesEntry.toContract(): SeriesEntry = SeriesEntry(name = name, sequence = sequence)

/**
 * Convert ABS sidecar chapters (seconds, optional ABS-internal id) to
 * domain chapters (millis, 1-based index). The ABS `id` field is its own
 * 0-based ordering used by the ABS UI; we re-derive a stable 1-based
 * `index` from list position to match [Chapter]'s contract.
 */
private fun List<AbsChapter>.toDomainChapters(): List<Chapter> =
    mapIndexed { i, c ->
        Chapter(
            index = i + 1,
            title = c.title,
            startMs = (c.start * 1000.0).toLong(),
            endMs = (c.end * 1000.0).toLong(),
        )
    }

private fun FolderShape.contributesAnything(): Boolean =
    titleFolder.isNotEmpty() || seriesFolder != null || authorFolder != null

private fun ParsedTitle.hasAnyFilenameAnnotation(): Boolean =
    asin != null || narrators.isNotEmpty() || publishedYear != null ||
        sequence != null || subtitle != null

private val NATURAL_TRACK_ORDER =
    compareBy<TrackEntry>(
        { it.discNumber ?: 0 },
        { it.trackNumber ?: Int.MAX_VALUE },
        { it.file.name },
    )

private suspend fun <T> safeRun(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
