package com.calypsan.listenup.server.scanner.pipeline

import com.calypsan.listenup.api.dto.scanner.AnalyzedBook
import com.calypsan.listenup.api.dto.scanner.BookChapterSource
import com.calypsan.listenup.api.dto.scanner.CandidateBook
import com.calypsan.listenup.api.dto.scanner.CoverSource
import com.calypsan.listenup.api.dto.scanner.FileEntry
import com.calypsan.listenup.api.dto.scanner.FileType
import com.calypsan.listenup.api.dto.scanner.MetadataStatus
import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.api.metadata.FieldSourceKind
import com.calypsan.listenup.api.dto.scanner.SeriesEntry
import com.calypsan.listenup.api.dto.scanner.TrackEntry
import com.calypsan.listenup.api.dto.scanner.TrackNumberSource
import com.calypsan.listenup.api.error.AudioMetadataError
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
import com.calypsan.listenup.server.scanner.sidecar.SidecarMetadata
import com.calypsan.listenup.server.scanner.sidecar.SidecarParser
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
            // Reuse the per-track parses already done in buildTracks for ordering.
            // Multi-track books are already parsed once there; synthesis reuses that
            // map instead of re-parsing every file. Single-track books and books
            // where no synthesis is needed pay nothing.
            //
            // This map is load-bearing for BOTH multi-file chapter synthesis AND multi-file
            // OverDrive-marker reconstruction — `pickChapters` reads per-track `tags.custom`
            // and `durationMs` from it. `shouldSynthesizeChapters` is the right gate for both:
            // it is true exactly when the book is multi-file with no higher-precedence chapter
            // source, which is also the only case the OverDrive branch is reachable. Keep them
            // aligned — narrowing this gate would silently starve the OverDrive path.
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
        perTrackMetadata: Map<TrackEntry, EmbeddedAudioMetadata?>,
    ): AnalyzedBook {
        val titleSourced = pickTitle(candidate, shape, parsed, embedded, metadata, sidecar)
        // A title always resolves; the rootRelPath fallback is path-derived, so its source is FOLDER.
        val titleKind = titleSourced?.kind ?: FieldSourceKind.FOLDER
        val rawTitle = titleSourced?.value ?: candidate.rootRelPath
        val (abridgedStripped, titleAbridged) = parseAbridgedFromTitle(rawTitle)
        // Strip a strict trailing series suffix the tag/album baked into the title (", Book 6",
        // "(Series, Book Two)", ": Series, Book 3"). Conservative — leaves prose series names alone.
        val cleanedTitle = SeriesSuffixMatcher.stripTrailingSeriesSuffix(abridgedStripped)

        // An explicit subtitle (metadata.json, embedded TIT3/freeform, OPF dc:subtitle, or the
        // gated folder " - " split) always wins; only when none exists do we derive one from the
        // title string. Applied uniformly to whatever source pickTitle chose.
        // Discard an explicit subtitle that is really the series (a mistagged SUBTITLE/TIT3), so the
        // real subtitle can be split out of the title below.
        val explicitSubtitle =
            firstSourced(
                FieldSourceKind.ABS_METADATA to metadata?.subtitle,
                FieldSourceKind.EMBEDDED to embedded?.tags?.subtitle,
                FieldSourceKind.SIDECAR to sidecar?.subtitle,
                FieldSourceKind.FILENAME to parsed.subtitle,
            )?.takeUnless { SeriesSuffixMatcher.isSeriesReference(it.value) }
        // subtitleKind is only meaningful when subtitle != null: an explicit subtitle carries its own
        // source; a subtitle split out of the title shares the title's source.
        val (title, subtitle, subtitleKind) =
            if (explicitSubtitle != null) {
                Triple(cleanedTitle, explicitSubtitle.value, explicitSubtitle.kind)
            } else {
                val (splitTitle, splitSubtitle) = TitleSubtitleSplitter.split(cleanedTitle)
                Triple(splitTitle, splitSubtitle, titleKind)
            }

        val authors = pickAuthors(shape, embedded, metadata, sidecar)
        val narrators = pickNarrators(parsed, embedded, metadata, sidecar)
        val seriesEntries = pickSeries(shape, parsed, embedded, metadata, sidecar)
        val publishedYear =
            firstSourced(
                FieldSourceKind.ABS_METADATA to metadata?.publishedYear,
                FieldSourceKind.EMBEDDED to embedded?.tags?.publishedYear,
                FieldSourceKind.SIDECAR to sidecar?.publishYear,
                FieldSourceKind.FILENAME to parsed.publishedYear,
            )
        val description =
            firstSourced(
                FieldSourceKind.ABS_METADATA to metadata?.description,
                FieldSourceKind.EMBEDDED to embedded?.tags?.description,
                FieldSourceKind.EMBEDDED to embedded?.tags?.custom?.get(AudioTags.COMMENT_KEY),
                FieldSourceKind.SIDECAR to sidecar?.description,
            )?.let { Sourced(HtmlToMarkdown.convert(it.value), it.kind) }
        val publisher =
            firstSourced(
                FieldSourceKind.ABS_METADATA to metadata?.publisher,
                FieldSourceKind.EMBEDDED to embedded?.tags?.publisher,
                FieldSourceKind.SIDECAR to sidecar?.publisher,
            )
        val language =
            firstSourced(
                FieldSourceKind.ABS_METADATA to metadata?.language,
                FieldSourceKind.EMBEDDED to embedded?.tags?.language,
                FieldSourceKind.SIDECAR to sidecar?.language,
            )?.let { Sourced(LanguageNormalizer.normalize(it.value), it.kind) }
        val genres = pickGenres(embedded, metadata, sidecar)

        // Per-field scan provenance (tier 0): one entry per resolved field, tagged with the source that
        // won it. Ties are already resolved by MetadataPrecedence at pick time. Fields with no scanned
        // value get no entry (a rescan that drops the value clears it, provenance with it).
        val fieldProvenance =
            buildMap {
                put(BookField.TITLE, FieldProvenance(titleKind))
                if (subtitle != null) put(BookField.SUBTITLE, FieldProvenance(subtitleKind))
                authors?.let { put(BookField.AUTHORS, FieldProvenance(it.kind)) }
                narrators?.let { put(BookField.NARRATORS, FieldProvenance(it.kind)) }
                seriesEntries?.let { put(BookField.SERIES, FieldProvenance(it.kind)) }
                publishedYear?.let { put(BookField.PUBLISH_YEAR, FieldProvenance(it.kind)) }
                description?.let { put(BookField.DESCRIPTION, FieldProvenance(it.kind)) }
                publisher?.let { put(BookField.PUBLISHER, FieldProvenance(it.kind)) }
                language?.let { put(BookField.LANGUAGE, FieldProvenance(it.kind)) }
                genres?.let { put(BookField.GENRES, FieldProvenance(it.kind)) }
            }

        val (resolvedChapters, chaptersSource) = pickChapters(embedded, metadata, tracks, perTrackMetadata, title)
        return AnalyzedBook(
            candidate = candidate,
            title = title,
            subtitle = subtitle,
            authors = authors?.value.orEmpty(),
            narrators = narrators?.value.orEmpty(),
            series = seriesEntries?.value.orEmpty(),
            publishedYear = publishedYear?.value,
            asin = metadata?.asin ?: embedded?.tags?.asin ?: parsed.asin,
            isbn = metadata?.isbn ?: embedded?.tags?.isbn,
            description = description?.value,
            publisher = publisher?.value,
            language = language?.value,
            genres = genres?.value.orEmpty(),
            tags = metadata?.tags.orEmpty(),
            abridged = metadata?.abridged ?: titleAbridged,
            explicit = metadata?.explicit,
            cover = cover,
            tracks = tracks,
            chapters = resolvedChapters,
            chaptersSource = chaptersSource,
            embedded = embedded,
            embeddedStatus = embeddedStatus,
            fieldProvenance = fieldProvenance,
            // A ParseError / UnsupportedFormat status means a file the scanner could
            // not fully read. MetadataStatus.Available and a null status (no audio
            // file, or a clean parse) are not warnings. On TOP of that, a book that
            // produced no usable duration (0-length looks normal to clients but can't
            // be scrubbed) or a multi-file book with a track whose length failed to
            // parse (a missing middle-file duration shifts every later chapter left —
            // silent whole-book corruption) is flagged honestly. (A9)
            hasScanWarning =
                embeddedStatus.isParseFailure() ||
                    durationScanWarning(
                        bookDurationMs = bookDurationMs(tracks, embedded),
                        trackDurations = tracks.map { it.durationMs },
                    ) ||
                    groupingScanWarning(
                        trackExtensions = tracks.map { it.file.ext },
                        distinctAlbumTags = distinctAlbumTagCount(perTrackMetadata),
                    ),
            documents = documentCollector.collect(rootPath, Path(rootPath, candidate.rootRelPath), candidate.files),
        )
    }

    /**
     * Chapter precedence:
     *   metadata.json (non-empty) → embedded (non-empty) → OverDrive markers →
     *   synthesized (multi-file) → empty.
     *
     * The sidecar wins when present so user-curated chapter titles in ABS survive
     * a rescan. OverDrive markers sit above synthesis: they carry real chapter
     * boundaries (even inside a single file, where synthesis produces nothing),
     * so they beat the one-chapter-per-track fallback. Synthesis activates only
     * when no higher source exists AND the book is multi-file.
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
            // Clamp against the file's own duration ONLY for a single-file book, where
            // `embedded.durationMs` is the authoritative end-of-book. For a multi-file book the
            // primary file's duration is not the book's, so a valid whole-book chapter set would be
            // wrongly truncated — there we only drop structurally-impossible chapters (null bound).
            val clampBound = if (tracks.size <= 1) embedded.durationMs else null
            return clampEmbeddedChapters(embedded.chapters, clampBound) to
                BookChapterSource.Embedded(embedded.chaptersSource)
        }
        // OverDrive/Libby marker chapters. Single-file books read the primary parse directly;
        // multi-file books reuse the per-track parses already captured for synthesis.
        val overdriveMetadata: (TrackEntry) -> EmbeddedAudioMetadata? =
            if (tracks.size <= 1) {
                { embedded }
            } else {
                { track -> perTrackMetadata[track] }
            }
        OverdriveChapters.parse(tracks, overdriveMetadata)?.let { chapters ->
            return chapters to BookChapterSource.Overdrive
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

    /** A resolved scan value paired with the tier-0 [FieldSourceKind] that won it. */
    private data class Sourced<out T>(
        val value: T,
        val kind: FieldSourceKind,
    )

    /** The scan-tier [FieldSourceKind] a precedence source maps to. */
    private fun MetadataPrecedenceSource.scanKind(): FieldSourceKind =
        when (this) {
            MetadataPrecedenceSource.ABS_METADATA -> FieldSourceKind.ABS_METADATA
            MetadataPrecedenceSource.EMBEDDED -> FieldSourceKind.EMBEDDED
            MetadataPrecedenceSource.SIDECAR -> FieldSourceKind.SIDECAR
            MetadataPrecedenceSource.FILENAME -> FieldSourceKind.FILENAME
            MetadataPrecedenceSource.FOLDER -> FieldSourceKind.FOLDER
        }

    /**
     * The first non-null value across [options] in the given order, tagged with its scan source. Mirrors
     * the fixed `?:` fallback chains for fields whose precedence isn't library-configurable.
     */
    private fun <T : Any> firstSourced(vararg options: Pair<FieldSourceKind, T?>): Sourced<T>? =
        options.firstNotNullOfOrNull { (kind, v) -> v?.let { Sourced(it, kind) } }

    private fun pickTitle(
        candidate: CandidateBook,
        shape: FolderShape,
        parsed: ParsedTitle,
        embedded: EmbeddedAudioMetadata?,
        metadata: AbsMetadata?,
        sidecar: SidecarMetadata?,
    ): Sourced<String>? {
        val multiFile = candidate.files.count { it.fileType == FileType.AUDIO } > 1
        return precedence.order.firstNotNullOfOrNull { source ->
            when (source) {
                MetadataPrecedenceSource.ABS_METADATA -> metadata?.title?.takeUnless { it.isBlank() }
                MetadataPrecedenceSource.EMBEDDED -> embeddedBookTitle(embedded, multiFile)
                MetadataPrecedenceSource.SIDECAR -> sidecar?.title?.takeUnless { it.isBlank() }
                MetadataPrecedenceSource.FILENAME -> parsed.title.takeUnless { it.isBlank() }
                MetadataPrecedenceSource.FOLDER -> shape.titleFolder.takeUnless { it.isBlank() }
            }?.let { Sourced(it, source.scanKind()) }
        }
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
    ): Sourced<List<String>>? =
        precedence.order.firstNotNullOfOrNull { source ->
            when (source) {
                MetadataPrecedenceSource.ABS_METADATA -> metadata?.authors?.takeIf { it.isNotEmpty() }
                MetadataPrecedenceSource.EMBEDDED -> embedded?.tags?.authors?.takeIf { it.isNotEmpty() }
                MetadataPrecedenceSource.SIDECAR -> sidecar.contributorNames(role = "author").takeIf { it.isNotEmpty() }
                MetadataPrecedenceSource.FILENAME -> null
                MetadataPrecedenceSource.FOLDER -> shape.authorFolder?.let { listOf(it) }
            }?.let { Sourced(it, source.scanKind()) }
        }

    private fun pickNarrators(
        parsed: ParsedTitle,
        embedded: EmbeddedAudioMetadata?,
        metadata: AbsMetadata?,
        sidecar: SidecarMetadata?,
    ): Sourced<List<String>>? =
        precedence.order.firstNotNullOfOrNull { source ->
            when (source) {
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
            }?.let { Sourced(it, source.scanKind()) }
        }

    private fun pickSeries(
        shape: FolderShape,
        parsed: ParsedTitle,
        embedded: EmbeddedAudioMetadata?,
        metadata: AbsMetadata?,
        sidecar: SidecarMetadata?,
    ): Sourced<List<SeriesEntry>>? =
        precedence.order.firstNotNullOfOrNull { source ->
            when (source) {
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
            }?.let { Sourced(it, source.scanKind()) }
        }

    private fun pickGenres(
        embedded: EmbeddedAudioMetadata?,
        metadata: AbsMetadata?,
        sidecar: SidecarMetadata?,
    ): Sourced<List<String>>? =
        precedence.order.firstNotNullOfOrNull { source ->
            when (source) {
                MetadataPrecedenceSource.ABS_METADATA -> metadata?.genres?.takeIf { it.isNotEmpty() }
                MetadataPrecedenceSource.EMBEDDED -> embedded?.tags?.genres?.takeIf { it.isNotEmpty() }
                MetadataPrecedenceSource.SIDECAR -> sidecar?.genres?.takeIf { it.isNotEmpty() }
                MetadataPrecedenceSource.FILENAME -> null
                MetadataPrecedenceSource.FOLDER -> null
            }?.let { Sourced(it, source.scanKind()) }
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
        genres = genres.ifEmpty { other.genres },
        contributors = contributors + other.contributors,
    )

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

/**
 * The book's effective total duration: the sum of per-track durations (multi-file books) or, when
 * those are absent (single-file books leave `TrackEntry.durationMs` null), the book-level embedded
 * duration. Mirrors `AnalyzedBookMapper`'s `totalDuration` derivation so the warning below reflects
 * what actually gets persisted.
 */
private fun bookDurationMs(
    tracks: List<TrackEntry>,
    embedded: EmbeddedAudioMetadata?,
): Long =
    tracks
        .sumOf { it.durationMs ?: 0L }
        .takeIf { it > 0L }
        ?: (embedded?.durationMs ?: 0L)

/**
 * True when a book warrants an operator scan warning for duration integrity: audio files exist but
 * yielded no usable book duration, or a multi-file book has a track whose duration failed to parse
 * (a 0-length track shifts every later chapter's offset left — F2-class silent corruption).
 */
internal fun durationScanWarning(
    bookDurationMs: Long,
    trackDurations: List<Long?>,
): Boolean {
    val audioPresent = trackDurations.isNotEmpty()
    val zeroLengthBook = audioPresent && bookDurationMs <= 0L
    val missingMultiTrackDuration =
        trackDurations.size >= 2 && trackDurations.any { (it ?: 0L) <= 0L }
    return zeroLengthBook || missingMultiTrackDuration
}

/** The number of distinct non-blank album tags across a multi-file book's per-track metadata. */
private fun distinctAlbumTagCount(perTrackMetadata: Map<TrackEntry, EmbeddedAudioMetadata?>): Int =
    perTrackMetadata.values
        .mapNotNull {
            it
                ?.tags
                ?.custom
                ?.get(AudioTags.ALBUM_KEY)
                ?.takeUnless(String::isBlank)
        }.distinct()
        .size

/**
 * True when a book's file shape looks wrong even though grouping (ABS convention) still produced one
 * book — a silent "library looks off" the operator should see (A10). Two heuristics, no grouping
 * change: mixed audio container formats in one book (e.g. `m4b` + `mp3` concatenated), or more than
 * one distinct album tag across its files (two books that landed in one folder).
 */
internal fun groupingScanWarning(
    trackExtensions: List<String>,
    distinctAlbumTags: Int,
): Boolean {
    val mixedFormats =
        trackExtensions
            .map { it.lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .size > 1
    return mixedFormats || distinctAlbumTags > 1
}

/**
 * Validates embedded chapter bounds at ingest (A9), re-indexing survivors 1-based and contiguous.
 *
 * Always dropped, regardless of [durationMs] — structurally impossible chapters a mistagged file can
 * carry: a negative start, or an end before its own start.
 *
 * Additionally, when [durationMs] is a known positive end-of-book (single-file books, where the
 * embedded duration is authoritative): a chapter starting at/after EOF is dropped, and a trailing
 * chapter whose end overruns EOF is clamped back to it. A `null`/`0` bound skips the EOF checks (a
 * multi-file book, whose primary file's duration is not the book's — clamping there would truncate a
 * valid whole-book chapter set).
 */
internal fun clampEmbeddedChapters(
    chapters: List<Chapter>,
    durationMs: Long?,
): List<Chapter> {
    val bound = durationMs?.takeIf { it > 0L }
    return chapters
        .mapNotNull { chapter ->
            when {
                chapter.startMs < 0L -> null
                chapter.endMs < chapter.startMs -> null
                bound != null && chapter.startMs >= bound -> null
                bound != null && chapter.endMs > bound -> chapter.copy(endMs = bound)
                else -> chapter
            }
        }.mapIndexed { i, chapter -> chapter.copy(index = i + 1) }
}

/**
 * Playback order for a book's tracks.
 *
 *  1. **Disc** — a file with no disc signal sorts *after* every known disc
 *     (`Int.MAX_VALUE`), so a disc-less bonus/intro file at the book root can't
 *     jump ahead of `CD1`/`CD2`.
 *  2. **Track** — a file with no track signal sorts last within its disc.
 *  3. **Filename** — natural (numeric-aware, case-insensitive) tiebreak, so
 *     `"1984 - 2.mp3"` precedes `"1984 - 10.mp3"` instead of the UTF-16
 *     lexicographic order that would place `10` before `2`.
 *
 * Tagged files (embedded track/disc) still win — they populate `trackNumber`/
 * `discNumber` before this comparator runs.
 */
private val NATURAL_TRACK_ORDER =
    compareBy<TrackEntry> { it.discNumber ?: Int.MAX_VALUE }
        .thenBy { it.trackNumber ?: Int.MAX_VALUE }
        .thenBy(NaturalFileNameOrder) { it.file.name }

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
