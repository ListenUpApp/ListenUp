package com.calypsan.listenup.api.dto.scanner

import com.calypsan.listenup.api.metadata.BookField
import com.calypsan.listenup.api.metadata.FieldProvenance
import com.calypsan.listenup.domain.embeddedmeta.Chapter
import com.calypsan.listenup.domain.embeddedmeta.EmbeddedAudioMetadata
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The Analyzer's output: a [CandidateBook] enriched with everything we can
 * infer from path components, filename annotations, embedded audio tags,
 * and `metadata.json`.
 *
 * Three concerns coexist on the shape, deliberately separated:
 *
 *  1. **Resolved view** — `title`/`authors`/`narrators`/`series`/etc. The
 *     merged result of every signal source after applying source
 *     precedence. UI list rendering reads these.
 *  2. **Raw signal** — [embedded] preserves the parser's output verbatim.
 *     Fields like real `durationMs`, `chapters`, and embedded `artwork`
 *     bytes are unique to this source — discarding them after merge would
 *     lose information no other source carries authoritatively.
 *  3. **Provenance** — [fieldProvenance] records, per resolved [BookField], the
 *     scan-tier source that won that field (folder/filename/sidecar/embedded/
 *     metadata.json); [embeddedStatus] records the parser outcome for the primary
 *     audio file (success, unsupported format, or typed parse error).
 *
 * Consumers reading the resolved view stay simple. Consumers needing
 * authoritative duration, chapter list, or artwork bytes read [embedded]
 * directly. Aggregators reading scan summaries group books by
 * [embeddedStatus].
 */
@Serializable
data class AnalyzedBook(
    @SerialName("candidate")
    val candidate: CandidateBook,
    val title: String,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val narrators: List<String> = emptyList(),
    val series: List<SeriesEntry> = emptyList(),
    val publishedYear: Int? = null,
    val asin: String? = null,
    val isbn: String? = null,
    val description: String? = null,
    val publisher: String? = null,
    val language: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val abridged: Boolean? = null,
    val explicit: Boolean? = null,
    val cover: CoverSource? = null,
    val tracks: List<TrackEntry> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val chaptersSource: BookChapterSource = BookChapterSource.None,
    val embedded: EmbeddedAudioMetadata? = null,
    val embeddedStatus: MetadataStatus? = null,
    /**
     * Per-field scan provenance: for each resolved [BookField] the scanner derived a value for, the
     * winning scan-tier source (all entries are tier 0 — [com.calypsan.listenup.api.metadata.FieldSourceKind.isScan]).
     * Ties are already broken by the library's `MetadataPrecedence` order at resolution time. Rides to
     * [com.calypsan.listenup.api.sync.BookSyncPayload.fieldProvenance] so a later enrichment/edit can be
     * tier-compared against the scan value.
     */
    val fieldProvenance: Map<BookField, FieldProvenance> = emptyMap(),
    /**
     * True when this book's embedded-metadata parse failed — its primary audio file is
     * corrupt or an unsupported format. The book is still produced (from folder/sidecar/
     * filename metadata); this flag surfaces a user-facing "double-check this book" advisory.
     */
    val hasScanWarning: Boolean = false,
    val documents: List<AnalyzedDocument> = emptyList(),
    /**
     * Absolute filesystem root of the library folder this book was walked from — the anchor
     * [candidate].rootRelPath is relative to. The persister resolves the book's `folder_id` and
     * reads its cover from THIS root, so a multi-folder library attributes every book to its own
     * folder (resolving one folder for the whole scan misplaces every non-primary-folder book,
     * which then fails to serve — a 404). `null` on books produced before folder attribution;
     * the persister falls back to the scan's primary root for those.
     */
    @SerialName("folderRootPath")
    val folderRootPath: String? = null,
)

/**
 * Returns a copy of this book with all embedded artwork bytes removed:
 * - [AnalyzedBook.cover] that is [CoverSource.Embedded] → null (bytes freed; filesystem covers kept)
 * - [AnalyzedBook.cover] that is [CoverSource.Spooled] → null (spool path reference freed; filesystem covers kept)
 * - [AnalyzedBook.embedded].artwork → null (bytes freed; other embedded fields kept)
 *
 * Used by [com.calypsan.listenup.server.scanner.Scanner] to build the `lastResult` snapshot
 * held between scans so artwork bytes and spool path references are not retained indefinitely
 * in heap after a scan completes.
 */
fun AnalyzedBook.withoutArtwork(): AnalyzedBook =
    copy(
        cover = if (cover is CoverSource.Embedded || cover is CoverSource.Spooled) null else cover,
        embedded = embedded?.copy(artwork = null),
    )
