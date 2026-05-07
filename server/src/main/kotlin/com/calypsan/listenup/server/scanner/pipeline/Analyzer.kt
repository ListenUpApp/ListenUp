package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.CoverSource
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.MetadataSource
import com.calypsan.listenup.api.dto.scanner.MetadataStatus
import com.calypsan.listenup.api.dto.scanner.SeriesEntry
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.error.AudioMetadataError
import com.calypsan.listenup.api.external.abs.AbsMetadata
import com.calypsan.listenup.client.core.AppResult
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
            compose(candidate, shape, parsed, tracks, cover, embedded, embeddedStatus, metadata)
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
    ): AnalyzedBook =
        AnalyzedBook(
            candidate = candidate,
            title = pickTitle(candidate, shape, parsed, embedded, metadata),
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
            embedded = embedded,
            embeddedStatus = embeddedStatus,
            sources = collectSources(shape, parsed, embedded, metadata),
        )

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
