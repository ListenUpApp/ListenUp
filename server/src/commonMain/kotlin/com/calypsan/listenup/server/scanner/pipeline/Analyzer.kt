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
import com.calypsan.listenup.api.dto.scanner.SidecarCuration
import com.calypsan.listenup.api.dto.scanner.SidecarCurationChapter
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.dto.scanner.TrackNumberSource
import com.calypsan.listenup.api.error.AudioMetadataError
import com.calypsan.listenup.api.sync.UserEditedField
import com.calypsan.listenup.api.external.abs.AbsChapter
import com.calypsan.listenup.api.external.abs.AbsMetadata
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.domain.embeddedmeta.AudioTags
import com.calypsan.listenup.domain.embeddedmeta.Chapter
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import com.calypsan.listenup.server.embeddedmeta.AudioFormatDetector
import com.calypsan.listenup.server.embeddedmeta.EmbeddedMetadataParser
import com.calypsan.listenup.server.scanner.inference.AbsTitleParser
import com.calypsan.listenup.server.scanner.inference.FolderShape
import com.calypsan.listenup.server.scanner.inference.ParsedTitle
import com.calypsan.listenup.server.scanner.inference.SeriesSuffixMatcher
import com.calypsan.listenup.server.scanner.inference.TrackInference
import com.calypsan.listenup.server.scanner.metadata.AbsMetadataReader
import com.calypsan.listenup.server.scanner.metadata.MetadataPrecedence
import com.calypsan.listenup.server.scanner.metadata.MetadataPrecedenceSource
import com.calypsan.listenup.server.scanner.document.DocumentCollector
import com.calypsan.listenup.server.scanner.sidecar.ListenUpSidecarReader
import com.calypsan.listenup.server.scanner.sidecar.SidecarMetadata
import com.calypsan.listenup.server.scanner.sidecar.SidecarParser
import com.calypsan.listenup.server.scanner.sidecar.SidecarReadResult
import com.calypsan.listenup.server.sidecar.ListenUpSidecar
import com.calypsan.listenup.server.logging.loggerFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.files.Path
import com.calypsan.listenup.domain.embeddedmeta.SeriesEntry as EmbeddedSeriesEntry

private val logger = loggerFor<Analyzer>()

/**
 * Stage 3 of the scanner pipeline: turns each [CandidateBook] into an
 * [AnalyzedBook] by combining four signal sources, in increasing precedence:
 *
 *  1. **Folder structure** — bottom-three components map to
 *     `<author>/<series>/<title>`.
 *  2. **Title-folder regex** — strips `[ASIN]`, `{Narrator}`, year prefix,
 *     volume/sequence prefix, optional subtitle. See [AbsTitleParser].
 *  3. **Sidecar files** — `.nfo`/`.opf`/`reader.txt`/`desc.txt` read-only
 *     enrichment files, parsed by [SidecarParser]s. Slot between filename
 *     and embedded tiers.
 *  4. **Embedded audio tags** — ID3v2/ID3v1 (MP3), MP4 `ilst` atoms, etc.
 *     Read from the primary audio file (first by stable track order) via
 *     [EmbeddedMetadataParser]. The parser is the only authoritative source
 *     for `durationMs`, `chapters`, and embedded `artwork` bytes — those
 *     fields survive on [AnalyzedBook.embedded] verbatim, while textual
 *     fields participate in the resolved view.
 *  5. **`metadata.json` overlay** — fields in a sidecar override
 *     embedded-, sidecar-, filename-, and folder-derived values when present.
 *
 * The textual chain's source ordering is operator-configurable via
 * [precedence]; the default ([MetadataPrecedence.DEFAULT]) is exactly the
 * high→low order listed above. A source omitted from the configured order is
 * never consulted for the textual `pick*` fields.
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
    private val sidecarParsers: List<SidecarParser> = emptyList(),
    private val precedence: MetadataPrecedence = MetadataPrecedence.DEFAULT,
    private val documentCollector: DocumentCollector = DocumentCollector(),
    private val listenUpSidecarReader: ListenUpSidecarReader? = null,
) {
    fun analyze(candidates: Flow<CandidateBook>): Flow<Result<AnalyzedBook>> =
        flow {
            candidates.collect { c -> emit(analyzeOne(c)) }
        }

    private suspend fun analyzeOne(candidate: CandidateBook): Result<AnalyzedBook> =
        safeRun(candidate.rootRelPath) {
            val shape = FolderShape.parse(candidate.rootRelPath)
            val parsed =
                AbsTitleParser.parse(
                    folder = shape.titleFolder,
                    hasSeriesFolder = shape.seriesFolder != null,
                    parseSubtitle = parseSubtitle,
                )
            val builtTracks = buildTracks(candidate)
            val tracks = builtTracks.tracks
            // Tiered book rule: a candidate that yields no playable track is not a
            // book — it's skipped, not ingested. Surfaces as a Result.failure so the
            // caller records it in ScanResult.errors and never in ScanResult.books.
            // A typed skip (carrying the candidate's file names) lets the scanner log an
            // actionable one-liner — a misnamed file like `Open Heavensm4b` (dropped dot) is
            // far more common here than a genuinely empty folder — without a noisy stacktrace.
            if (tracks.isEmpty()) {
                throw NoRecognizedAudio(candidate.files.map { it.name })
            }
            val primaryAudio = tracks.firstOrNull()?.file
            val (embedded, embeddedStatus) = parseEmbedded(primaryAudio)
            val cover = resolveCover(candidate.files, embedded)
            val metadata = readMetadata(candidate)
            val sidecar = parseSidecars(candidate)
            // The ListenUp curation sidecar — consulted at the top precedence slot. A SelfWritten
            // file (hash-matches our own last write) is skipped entirely: re-ingesting our own
            // output would be a no-op echo at best and a write loop at worst.
            val listenUp =
                (listenUpSidecarReader?.read(Path(rootPath, candidate.rootRelPath)) as? SidecarReadResult.External)
                    ?.sidecar
            // Reuse the per-track parses already done in buildTracks for ordering.
            // Multi-track books are already parsed once there; synthesis reuses that
            // map instead of re-parsing every file. Single-track books and books
            // where no synthesis is needed pay nothing.
            val perTrackMetadata: Map<TrackEntry, EmbeddedAudioMetadata?> =
                if (shouldSynthesizeChapters(metadata, embedded, tracks)) {
                    builtTracks.perTrackMetadata.ifEmpty { parseAllTrackDurations(tracks) }
                } else {
                    emptyMap()
                }
            compose(
                candidate,
                shape,
                parsed,
                tracks,
                cover,
                embedded,
                embeddedStatus,
                metadata,
                sidecar,
                listenUp,
                perTrackMetadata,
            )
        }

    private suspend fun buildTracks(candidate: CandidateBook): BuiltTracks {
        val audioFiles = candidate.files.filter { it.fileType == FileType.AUDIO }
        val multiTrack = audioFiles.size > 1
        val perTrackMetadata = LinkedHashMap<TrackEntry, EmbeddedAudioMetadata?>(audioFiles.size)
        val tracks =
            audioFiles
                .map { file ->
                    val parentFolder =
                        file.relPath
                            .split('/')
                            .dropLast(1)
                            .lastOrNull()
                    val info = TrackInference.infer(file.name, parentFolder)
                    // Embedded track/disc numbers are stronger than a filename guess (ABS parity).
                    // Parse per-track only for multi-track books — a lone file needs no ordering.
                    val parsed = if (multiTrack) parseTrackForDuration(file) else null
                    val embTags = parsed?.tags
                    val embTrack = embTags?.trackNumber
                    val embDisc = embTags?.discNumber
                    val entry =
                        TrackEntry(
                            file = file,
                            trackNumber = embTrack ?: info.trackNumber,
                            discNumber = embDisc ?: info.discNumber,
                            trackSource = if (embTrack != null) TrackNumberSource.METADATA else info.trackSource,
                            discSource = if (embDisc != null) TrackNumberSource.METADATA else info.discSource,
                            // Per-track length for multi-file books, so the mapper can sum the book's
                            // total duration and give each file its real length (parsed is null for
                            // single-file books, where the book-level embedded duration suffices).
                            durationMs = parsed?.durationMs,
                        )
                    // Capture the full parse result so synthesis can reuse it without re-parsing.
                    if (multiTrack) perTrackMetadata[entry] = parsed
                    entry
                }.sortedWith(NATURAL_TRACK_ORDER)
        return BuiltTracks(tracks = tracks, perTrackMetadata = perTrackMetadata)
    }

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
        val absolutePath = Path(rootPath, primaryAudio.relPath)
        return when (val result = embeddedMetadataParser.parse(absolutePath)) {
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
        val absolutePath = Path(rootPath, file.relPath)
        return when (val result = embeddedMetadataParser.parse(absolutePath)) {
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
        return metadataReader.read(Path(rootPath, sidecar.relPath))
    }

    /**
     * Routes each candidate file to a matching [SidecarParser] and merges the
     * parsed [SidecarMetadata]s into one.
     *
     * Returns `null` when no file matched any parser. A parser that returns
     * `null` (unparseable file) or throws (a defect — the contract says return
     * `null`) is treated as contributing nothing; the scan continues.
     * `CancellationException` is always re-raised.
     *
     * **Merge rule:** files are processed in [CandidateBook.files] order;
     * for each field the first non-null value wins. Order is deterministic
     * because the Walker emits files in a stable order.
     */
    private suspend fun parseSidecars(candidate: CandidateBook): SidecarMetadata? {
        var merged: SidecarMetadata? = null
        for (file in candidate.files) {
            val parser =
                sidecarParsers.firstOrNull { p ->
                    p.supportedFilenames.any { it.equals(file.name, ignoreCase = true) } ||
                        p.supportedExtensions.any { it.equals(file.ext, ignoreCase = true) }
                } ?: continue
            val parsed = runParserSafely(parser, file) ?: continue
            merged = merged?.mergedWith(parsed) ?: parsed
        }
        return merged
    }

    private suspend fun runParserSafely(
        parser: SidecarParser,
        file: FileEntry,
    ): SidecarMetadata? =
        try {
            parser.parse(Path(rootPath, file.relPath))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logger.warn(e) { "sidecar parser ${parser::class.simpleName} threw on ${file.relPath}; treating as null" }
            null
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
        sidecar: SidecarMetadata?,
        listenUp: ListenUpSidecar?,
        perTrackMetadata: Map<TrackEntry, EmbeddedAudioMetadata?>,
    ): AnalyzedBook {
        val rawTitle = pickTitle(candidate, shape, parsed, embedded, metadata, sidecar, listenUp)
        // A title sourced from the ListenUp curation sidecar is the user's exact words —
        // it bypasses the abridged-parse / series-suffix-strip / subtitle-split cleanup
        // chain that exists to de-junk scanner-derived titles.
        val curatedTitleWins = rawTitle == listenUp?.metadata?.title?.takeUnless { it.isBlank() }
        val (abridgedStripped, titleAbridged) =
            if (curatedTitleWins) rawTitle to false else parseAbridgedFromTitle(rawTitle)
        // Strip a strict trailing series suffix the tag/album baked into the title (", Book 6",
        // "(Series, Book Two)", ": Series, Book 3"). Conservative — leaves prose series names alone.
        val cleanedTitle =
            if (curatedTitleWins) abridgedStripped else SeriesSuffixMatcher.stripTrailingSeriesSuffix(abridgedStripped)

        // An explicit subtitle (metadata.json, embedded TIT3/freeform, OPF dc:subtitle, or the
        // gated folder " - " split) always wins; only when none exists do we derive one from the
        // title string. Applied uniformly to whatever source pickTitle chose.
        // Discard an explicit subtitle that is really the series (a mistagged SUBTITLE/TIT3), so the
        // real subtitle can be split out of the title below.
        val explicitSubtitle =
            listenUp?.metadata?.subtitle
                ?: (
                    metadata?.subtitle
                        ?: embedded?.tags?.subtitle
                        ?: sidecar?.subtitle
                        ?: parsed.subtitle
                )?.takeUnless { SeriesSuffixMatcher.isSeriesReference(it) }
        val (title, subtitle) =
            when {
                explicitSubtitle != null -> cleanedTitle to explicitSubtitle

                curatedTitleWins -> cleanedTitle to null

                // never split a user-curated title
                else -> TitleSubtitleSplitter.split(cleanedTitle)
            }

        val (resolvedChapters, chaptersSource) = pickChapters(embedded, metadata, tracks, perTrackMetadata, title)
        return AnalyzedBook(
            candidate = candidate,
            title = title,
            subtitle = subtitle,
            authors = pickAuthors(shape, embedded, metadata, sidecar, listenUp),
            narrators = pickNarrators(parsed, embedded, metadata, sidecar, listenUp),
            series = pickSeries(shape, parsed, embedded, metadata, sidecar, listenUp),
            publishedYear =
                metadata?.publishedYear
                    ?: embedded?.tags?.publishedYear
                    ?: sidecar?.publishYear
                    ?: parsed.publishedYear,
            asin = metadata?.asin ?: embedded?.tags?.asin ?: parsed.asin,
            isbn = metadata?.isbn ?: embedded?.tags?.isbn,
            description =
                (
                    listenUp?.metadata?.description
                        ?: metadata?.description
                        ?: embedded?.tags?.description
                        ?: embedded?.tags?.custom?.get(AudioTags.COMMENT_KEY)
                        ?: sidecar?.description
                )?.let { HtmlToMarkdown.convert(it) },
            publisher = metadata?.publisher ?: embedded?.tags?.publisher ?: sidecar?.publisher,
            language =
                (metadata?.language ?: embedded?.tags?.language ?: sidecar?.language)
                    ?.let { LanguageNormalizer.normalize(it) },
            genres = pickGenres(embedded, metadata, listenUp),
            tags = listenUp?.metadata?.tags?.takeIf { it.isNotEmpty() } ?: metadata?.tags.orEmpty(),
            abridged = metadata?.abridged ?: titleAbridged,
            explicit = metadata?.explicit,
            cover = cover,
            tracks = tracks,
            chapters = resolvedChapters,
            chaptersSource = chaptersSource,
            embedded = embedded,
            embeddedStatus = embeddedStatus,
            sources = collectSources(shape, parsed, embedded, metadata, sidecar),
            // A ParseError / UnsupportedFormat status means a file the scanner could
            // not fully read. MetadataStatus.Available and a null status (no audio
            // file, or a clean parse) are not warnings.
            hasScanWarning = embeddedStatus.isParseFailure(),
            documents = documentCollector.collect(rootPath, Path(rootPath, candidate.rootRelPath), candidate.files),
            sidecarCuration = listenUp?.toCuration(),
        )
    }

    /**
     * Chapter precedence:
     *   metadata.json (non-empty) → embedded (non-empty) → synthesized (multi-file) → empty.
     *
     * The sidecar wins when present so user-curated chapter titles in ABS survive
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
        sidecar: SidecarMetadata?,
        listenUp: ListenUpSidecar?,
    ): String {
        val multiFile = candidate.files.count { it.fileType == FileType.AUDIO } > 1
        return precedence.order.firstNotNullOfOrNull { source ->
            when (source) {
                MetadataPrecedenceSource.LISTENUP -> listenUp?.metadata?.title?.takeUnless { it.isBlank() }
                MetadataPrecedenceSource.ABS_METADATA -> metadata?.title?.takeUnless { it.isBlank() }
                MetadataPrecedenceSource.EMBEDDED -> embeddedBookTitle(embedded, multiFile)
                MetadataPrecedenceSource.SIDECAR -> sidecar?.title?.takeUnless { it.isBlank() }
                MetadataPrecedenceSource.FILENAME -> parsed.title.takeUnless { it.isBlank() }
                MetadataPrecedenceSource.FOLDER -> shape.titleFolder.takeUnless { it.isBlank() }
            }
        } ?: candidate.rootRelPath
    }

    /**
     * The book title carried by embedded tags:
     *  - **Multi-file book:** the per-file `title` tag is the current track's *chapter* title, so it
     *    is ignored; the **album** tag is the book title (authoritative, matching Audiobookshelf).
     *  - **Single-file book:** the `title` tag *is* the book title and is usually cleaner than the
     *    album (which often appends the series, e.g. `"…: Chaos Seeds, Book 3"`), so it wins; the
     *    album is only a fallback when there is no title tag.
     *
     * Returns null when embedded tags carry no usable book title, letting [pickTitle] fall through
     * to the folder.
     */
    private fun embeddedBookTitle(
        embedded: EmbeddedAudioMetadata?,
        multiFile: Boolean,
    ): String? {
        val tags = embedded?.tags ?: return null
        val album = tags.custom[AudioTags.ALBUM_KEY]?.takeUnless { it.isBlank() }
        return if (multiFile) album else tags.title?.takeUnless { it.isBlank() } ?: album
    }

    private fun pickAuthors(
        shape: FolderShape,
        embedded: EmbeddedAudioMetadata?,
        metadata: AbsMetadata?,
        sidecar: SidecarMetadata?,
        listenUp: ListenUpSidecar?,
    ): List<String> =
        precedence.order.firstNotNullOfOrNull { source ->
            when (source) {
                MetadataPrecedenceSource.LISTENUP -> {
                    listenUp
                        .contributorNames(
                            role = "author",
                        ).takeIf { it.isNotEmpty() }
                }

                MetadataPrecedenceSource.ABS_METADATA -> {
                    metadata?.authors?.takeIf { it.isNotEmpty() }
                }

                MetadataPrecedenceSource.EMBEDDED -> {
                    embedded?.tags?.authors?.takeIf { it.isNotEmpty() }
                }

                MetadataPrecedenceSource.SIDECAR -> {
                    sidecar.contributorNames(role = "author").takeIf { it.isNotEmpty() }
                }

                MetadataPrecedenceSource.FILENAME -> {
                    null
                }

                MetadataPrecedenceSource.FOLDER -> {
                    shape.authorFolder?.let { listOf(it) }
                }
            }
        } ?: emptyList()

    private fun pickNarrators(
        parsed: ParsedTitle,
        embedded: EmbeddedAudioMetadata?,
        metadata: AbsMetadata?,
        sidecar: SidecarMetadata?,
        listenUp: ListenUpSidecar?,
    ): List<String> =
        precedence.order.firstNotNullOfOrNull { source ->
            when (source) {
                MetadataPrecedenceSource.LISTENUP -> {
                    listenUp.contributorNames(role = "narrator").takeIf { it.isNotEmpty() }
                }

                MetadataPrecedenceSource.ABS_METADATA -> {
                    metadata?.narrators?.takeIf { it.isNotEmpty() }
                }

                MetadataPrecedenceSource.EMBEDDED -> {
                    embedded?.tags?.narrators?.takeIf { it.isNotEmpty() }
                }

                MetadataPrecedenceSource.SIDECAR -> {
                    sidecar
                        .contributorNames(
                            role = "narrator",
                        ).takeIf { it.isNotEmpty() }
                }

                MetadataPrecedenceSource.FILENAME -> {
                    parsed.narrators.takeIf { it.isNotEmpty() }
                }

                MetadataPrecedenceSource.FOLDER -> {
                    null
                }
            }
        } ?: emptyList()

    private fun pickSeries(
        shape: FolderShape,
        parsed: ParsedTitle,
        embedded: EmbeddedAudioMetadata?,
        metadata: AbsMetadata?,
        sidecar: SidecarMetadata?,
        listenUp: ListenUpSidecar?,
    ): List<SeriesEntry> =
        precedence.order.firstNotNullOfOrNull { source ->
            when (source) {
                MetadataPrecedenceSource.LISTENUP -> {
                    listenUp
                        ?.metadata
                        ?.series
                        .orEmpty()
                        .map { SeriesEntry(name = it.name, sequence = it.sequence) }
                        .takeIf { it.isNotEmpty() }
                }

                MetadataPrecedenceSource.ABS_METADATA -> {
                    metadataReader.parseSeriesEntries(metadata?.series.orEmpty()).takeIf { it.isNotEmpty() }
                }

                MetadataPrecedenceSource.EMBEDDED -> {
                    embedded
                        ?.tags
                        ?.series
                        .orEmpty()
                        .map(EmbeddedSeriesEntry::toContract)
                        .takeIf { it.isNotEmpty() }
                }

                MetadataPrecedenceSource.SIDECAR -> {
                    sidecar?.series?.takeIf { it.isNotEmpty() }
                }

                MetadataPrecedenceSource.FILENAME -> {
                    null
                }

                MetadataPrecedenceSource.FOLDER -> {
                    shape.seriesFolder?.let { listOf(SeriesEntry(name = it, sequence = parsed.sequence)) }
                }
            }
        } ?: emptyList()

    private fun pickGenres(
        embedded: EmbeddedAudioMetadata?,
        metadata: AbsMetadata?,
        listenUp: ListenUpSidecar?,
    ): List<String> =
        precedence.order.firstNotNullOfOrNull { source ->
            when (source) {
                MetadataPrecedenceSource.LISTENUP -> listenUp?.metadata?.genres?.takeIf { it.isNotEmpty() }
                MetadataPrecedenceSource.ABS_METADATA -> metadata?.genres?.takeIf { it.isNotEmpty() }
                MetadataPrecedenceSource.EMBEDDED -> embedded?.tags?.genres?.takeIf { it.isNotEmpty() }
                MetadataPrecedenceSource.SIDECAR -> null
                MetadataPrecedenceSource.FILENAME -> null
                MetadataPrecedenceSource.FOLDER -> null
            }
        } ?: emptyList()

    private fun collectSources(
        shape: FolderShape,
        parsed: ParsedTitle,
        embedded: EmbeddedAudioMetadata?,
        metadata: AbsMetadata?,
        sidecar: SidecarMetadata?,
    ): Set<MetadataSource> {
        val sources = mutableSetOf<MetadataSource>()
        if (shape.contributesAnything()) sources += MetadataSource.FOLDER_STRUCTURE
        if (parsed.hasAnyFilenameAnnotation()) sources += MetadataSource.FILENAME
        if (sidecar != null) sources += MetadataSource.SIDECAR
        if (embedded != null) sources += MetadataSource.AUDIO_METATAGS
        if (metadata != null) sources += MetadataSource.ABS_METADATA
        return sources
    }

    /**
     * The output of [buildTracks]: the ordered track list together with the
     * per-track parse results already captured for multi-track books.
     *
     * [perTrackMetadata] is non-empty only for multi-track books (>1 audio
     * file); synthesis reuses it to avoid a second parse round. Single-track
     * books produce an empty map — they never need ordering parses and their
     * primary metadata comes from [parseEmbedded].
     */
    private data class BuiltTracks(
        val tracks: List<TrackEntry>,
        val perTrackMetadata: Map<TrackEntry, EmbeddedAudioMetadata?>,
    )
}

/** Field-by-field merge: the receiver's non-null values win; [other] fills the gaps. */
private fun SidecarMetadata.mergedWith(other: SidecarMetadata): SidecarMetadata =
    SidecarMetadata(
        title = title ?: other.title,
        subtitle = subtitle ?: other.subtitle,
        description = description ?: other.description,
        publishYear = publishYear ?: other.publishYear,
        publisher = publisher ?: other.publisher,
        language = language ?: other.language,
        series = series.ifEmpty { other.series },
        contributors = contributors + other.contributors,
    )

/**
 * The scanner→persist curation payload from an External ListenUp sidecar: user-edit
 * provenance (unknown field names dropped for forward compat) plus USER chapters.
 */
private fun ListenUpSidecar.toCuration(): SidecarCuration =
    SidecarCuration(
        userEditedFields =
            userEditedFields
                .mapNotNullTo(mutableSetOf()) { name -> UserEditedField.entries.firstOrNull { it.name == name } },
        userChapters =
            chapters
                ?.takeIf { it.source.equals("USER", ignoreCase = true) }
                ?.entries
                ?.map { SidecarCurationChapter(title = it.title, startMs = it.startMs) },
    )

/** Names of ListenUp-sidecar contributors with the given [role] (case-insensitive). */
private fun ListenUpSidecar?.contributorNames(role: String): List<String> =
    this
        ?.metadata
        ?.contributors
        .orEmpty()
        .filter { it.role.equals(role, ignoreCase = true) }
        .map { it.name }

/** Names of sidecar contributors with the given [role] (case-insensitive). */
private fun SidecarMetadata?.contributorNames(role: String): List<String> =
    this
        ?.contributors
        .orEmpty()
        .filter { it.role.equals(role, ignoreCase = true) }
        .map { it.name }

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

/**
 * True when the parser outcome is a failure the user should know about —
 * [MetadataStatus.UnsupportedFormat] or [MetadataStatus.ParseError]. A `null`
 * status (no audio file) and [MetadataStatus.Available] (a clean parse) are not.
 */
private fun MetadataStatus?.isParseFailure(): Boolean =
    this is MetadataStatus.UnsupportedFormat || this is MetadataStatus.ParseError

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

/**
 * A candidate that contains files but none recognized as audio. It is *not* a book, so it is
 * skipped (an expected outcome, not a fault). [files] are the candidate's file names — usually a
 * single misnamed file (e.g. `Open Heavensm4b` with the extension dot dropped) — so the scanner can
 * log an actionable one-liner instead of a stacktrace. The composed [message] is reused verbatim as
 * the resulting `ScanError`'s detail.
 */
internal class NoRecognizedAudio(
    val files: List<String>,
) : Exception(message(files)) {
    private companion object {
        private const val MAX_LISTED = 5

        fun message(files: List<String>): String {
            val found =
                if (files.isEmpty()) {
                    ""
                } else {
                    val shown = files.take(MAX_LISTED).joinToString()
                    val more = if (files.size > MAX_LISTED) ", …" else ""
                    " (found: $shown$more)"
                }
            return "no recognized audio files$found — check the file extension"
        }
    }
}

/**
 * Wraps any per-book analysis failure with the candidate's `rootRelPath` so
 * the scanner can name the *failing book's* directory in its `ScanError`
 * rather than the library root.
 */
internal class BookAnalysisFailure(
    val rootRelPath: String,
    cause: Throwable,
) : Exception(cause.message ?: cause::class.simpleName ?: "unknown error", cause)

/**
 * Runs [block], converting any non-cancellation throwable into a
 * `Result.failure` carrying a [BookAnalysisFailure] tagged with [rootRelPath].
 * `CancellationException` is always re-raised so structured concurrency stays intact.
 */
private suspend fun <T> safeRun(
    rootRelPath: String,
    block: suspend () -> T,
): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(BookAnalysisFailure(rootRelPath, e))
    }
