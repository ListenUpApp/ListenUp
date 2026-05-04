package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.MetadataSource
import com.calypsan.listenup.api.dto.scanner.SeriesEntry
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.external.abs.AbsMetadata
import com.calypsan.listenup.server.scanner.inference.AbsTitleParser
import com.calypsan.listenup.server.scanner.inference.FolderShape
import com.calypsan.listenup.server.scanner.inference.ParsedTitle
import com.calypsan.listenup.server.scanner.inference.TrackInference
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.file.Path

/**
 * Stage 3 of the scanner pipeline: turns each [CandidateBook] into an
 * [AnalyzedBook] by combining three signal sources, in increasing precedence:
 *
 *  1. **Folder structure** — bottom-three components map to
 *     `<author>/<series>/<title>`.
 *  2. **Title-folder regex** — strips `[ASIN]`, `{Narrator}`, year prefix,
 *     volume/sequence prefix, optional subtitle. See [AbsTitleParser].
 *  3. **`metadata.json` overlay** — fields in a sidecar override
 *     folder-derived values when present.
 *
 * Phase 3 will plug in embedded ID3/M4B tags as a fourth signal between
 * filename and metadata.json. [AnalyzedBook]'s shape already accommodates
 * those fields (every embedded-derivable field is nullable).
 *
 * Per-book failures (unexpected exceptions during analysis) surface as
 * `Result.failure`; the upstream caller decides whether to log-and-continue
 * or abort the scan. `CancellationException` is always re-raised so
 * structured concurrency stays intact.
 */
class Analyzer(
    private val rootPath: Path,
    private val metadataReader: AbsMetadataReader,
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
            val cover = pickCover(candidate.files)
            val metadata = readMetadata(candidate)
            compose(candidate, shape, parsed, tracks, cover, metadata)
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

    private fun pickCover(files: List<FileEntry>): FileEntry? {
        val images = files.filter { it.fileType == FileType.IMAGE }
        val coverByName =
            images.firstOrNull {
                it.name.substringBeforeLast('.').equals("cover", ignoreCase = true)
            }
        return coverByName ?: images.firstOrNull()
    }

    private suspend fun readMetadata(candidate: CandidateBook): AbsMetadata? {
        val sidecar =
            candidate.files.firstOrNull {
                it.fileType == FileType.METADATA && it.name.equals("metadata.json", ignoreCase = true)
            } ?: return null
        return metadataReader.read(rootPath.resolve(sidecar.relPath))
    }

    private fun compose(
        candidate: CandidateBook,
        shape: FolderShape,
        parsed: ParsedTitle,
        tracks: List<TrackEntry>,
        cover: FileEntry?,
        metadata: AbsMetadata?,
    ): AnalyzedBook =
        AnalyzedBook(
            candidate = candidate,
            title = pickTitle(candidate, shape, parsed, metadata),
            subtitle = metadata?.subtitle ?: parsed.subtitle,
            authors = pickAuthors(shape, metadata),
            narrators = pickNarrators(parsed, metadata),
            series = pickSeries(shape, parsed, metadata),
            publishedYear = metadata?.publishedYear ?: parsed.publishedYear,
            asin = metadata?.asin ?: parsed.asin,
            isbn = metadata?.isbn,
            description = metadata?.description,
            publisher = metadata?.publisher,
            language = metadata?.language,
            genres = metadata?.genres.orEmpty(),
            tags = metadata?.tags.orEmpty(),
            abridged = metadata?.abridged,
            explicit = metadata?.explicit,
            cover = cover,
            tracks = tracks,
            sources = collectSources(shape, parsed, metadata),
        )

    private fun pickTitle(
        candidate: CandidateBook,
        shape: FolderShape,
        parsed: ParsedTitle,
        metadata: AbsMetadata?,
    ): String =
        metadata?.title?.takeUnless { it.isBlank() }
            ?: parsed.title.takeUnless { it.isBlank() }
            ?: shape.titleFolder.takeUnless { it.isBlank() }
            ?: candidate.rootRelPath

    private fun pickAuthors(
        shape: FolderShape,
        metadata: AbsMetadata?,
    ): List<String> =
        metadata?.authors?.takeIf { it.isNotEmpty() }
            ?: shape.authorFolder?.let { listOf(it) }
            ?: emptyList()

    private fun pickNarrators(
        parsed: ParsedTitle,
        metadata: AbsMetadata?,
    ): List<String> = metadata?.narrators?.takeIf { it.isNotEmpty() } ?: parsed.narrators

    private fun pickSeries(
        shape: FolderShape,
        parsed: ParsedTitle,
        metadata: AbsMetadata?,
    ): List<SeriesEntry> {
        val fromMetadata = metadataReader.parseSeriesEntries(metadata?.series.orEmpty())
        if (fromMetadata.isNotEmpty()) return fromMetadata
        return shape.seriesFolder
            ?.let { listOf(SeriesEntry(name = it, sequence = parsed.sequence)) }
            ?: emptyList()
    }

    private fun collectSources(
        shape: FolderShape,
        parsed: ParsedTitle,
        metadata: AbsMetadata?,
    ): Set<MetadataSource> {
        val sources = mutableSetOf<MetadataSource>()
        if (shape.contributesAnything()) sources += MetadataSource.FOLDER_STRUCTURE
        if (parsed.hasAnyFilenameAnnotation()) sources += MetadataSource.FILENAME
        if (metadata != null) sources += MetadataSource.ABS_METADATA
        return sources
    }
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
