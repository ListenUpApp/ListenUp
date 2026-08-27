package com.calypsan.listenup.server.scanner.sidecar

import com.calypsan.listenup.api.dto.scanner.SeriesEntry
import com.calypsan.listenup.server.io.hashBytesSha256
import com.calypsan.listenup.server.io.readBytes
import com.calypsan.listenup.server.logging.loggerFor
import com.calypsan.listenup.server.sidecar.ListenUpSidecar
import com.calypsan.listenup.server.sidecar.SidecarJson
import com.calypsan.listenup.server.sidecar.SidecarWriteStateRepository
import kotlinx.coroutines.CancellationException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

private val logger = loggerFor<ListenUpSidecarReader>()

/** The sidecar's on-disk filename — mirrors `SidecarWriter.SIDECAR_FILENAME`. */
private const val LISTENUP_SIDECAR_FILENAME = "listenup.json"

/**
 * The outcome of reading a book directory's `listenup.json`, discriminated by the
 * round-trip content hash recorded in `sidecar_write_state`.
 */
internal sealed interface SidecarReadResult {
    /** No `listenup.json` in the book directory (or it was unreadable/corrupt). */
    data object Absent : SidecarReadResult

    /**
     * The file's bytes hash to a recorded self-write — it is exactly what this server last
     * wrote, so re-ingesting it would be a no-op echo. The scanner skips it.
     */
    data object SelfWritten : SidecarReadResult

    /** A parseable sidecar this server did NOT write (fresh DB, friend's file, hand edit) — ingest it. */
    data class External(
        val sidecar: ListenUpSidecar,
    ) : SidecarReadResult
}

/**
 * Reads a book directory's `listenup.json` into a [SidecarReadResult] — the read half of
 * the sidecar round trip, consulted by the Analyzer at the
 * [com.calypsan.listenup.server.scanner.metadata.MetadataPrecedenceSource.LISTENUP] (top)
 * precedence slot.
 *
 * Implements [SidecarParser] so the `SidecarParsersAreReadOnly` Konsist rule pins its
 * read-only contract, but it is deliberately NOT registered in the Analyzer's generic
 * parser list: the ListenUp sidecar carries provenance and chapters the flat
 * [SidecarMetadata] can't, and it owns a distinct top-precedence slot — registering it
 * generically would double-ingest its fields at the wrong (SIDECAR) tier. [parse] is the
 * honest flat projection for any future caller that wants only the metadata block; the
 * Analyzer calls [read].
 */
internal class ListenUpSidecarReader(
    private val writeState: SidecarWriteStateRepository,
) : SidecarParser {
    override val supportedFilenames: Set<String> = setOf(LISTENUP_SIDECAR_FILENAME)
    override val supportedExtensions: Set<String> = emptySet()

    override suspend fun parse(file: Path): SidecarMetadata? {
        val bytes = readBytesOrNull(file) ?: return null
        val model = SidecarJson.parseOrNull(bytes) ?: return null
        return model.toSidecarMetadata()
    }

    /**
     * Reads `[bookDir]/listenup.json` and classifies it: [SidecarReadResult.Absent] when
     * missing/unreadable/corrupt (never an error — the scan continues without it),
     * [SidecarReadResult.SelfWritten] when the bytes hash-match a recorded self-write, and
     * [SidecarReadResult.External] otherwise.
     */
    suspend fun read(bookDir: Path): SidecarReadResult {
        val file = Path(bookDir, LISTENUP_SIDECAR_FILENAME)
        if (!SystemFileSystem.exists(file)) return SidecarReadResult.Absent
        val bytes = readBytesOrNull(file) ?: return SidecarReadResult.Absent
        if (writeState.isSelfWrittenHash(hashBytesSha256(bytes))) return SidecarReadResult.SelfWritten
        val model = SidecarJson.parseOrNull(bytes) ?: return SidecarReadResult.Absent
        return SidecarReadResult.External(model)
    }

    private fun readBytesOrNull(file: Path): ByteArray? =
        try {
            file.readBytes()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "unreadable listenup.json at $file — treating as absent" }
            null
        }
}

/** Flat projection of the sidecar's metadata block onto the generic [SidecarMetadata] shape. */
private fun ListenUpSidecar.toSidecarMetadata(): SidecarMetadata =
    SidecarMetadata(
        title = metadata.title,
        subtitle = metadata.subtitle,
        description = metadata.description,
        series = metadata.series.map { SeriesEntry(name = it.name, sequence = it.sequence) },
        contributors = metadata.contributors.map { SidecarContributor(name = it.name, role = it.role) },
    )
