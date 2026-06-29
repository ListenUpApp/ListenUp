package com.calypsan.listenup.server.scanner.sidecar

import kotlinx.io.files.Path

/**
 * Reads a metadata sidecar file into [SidecarMetadata]. Sidecars are read-only
 * enrichment inputs — a parser MUST NOT write to disk. (The Konsist rule
 * `SidecarParsersAreReadOnly` enforces this.)
 *
 * A parser declares the filenames and/or extensions it handles. The
 * [com.calypsan.listenup.server.scanner.pipeline.Analyzer] routes each
 * candidate sidecar file to the matching parser.
 */
internal interface SidecarParser {
    /** Exact filenames this parser handles, e.g. `{"reader.txt"}`. Case-insensitive match. */
    val supportedFilenames: Set<String>

    /** File extensions this parser handles, e.g. `{"nfo"}`. Case-insensitive, no leading dot. */
    val supportedExtensions: Set<String>

    /**
     * Parse [file] into [SidecarMetadata], or `null` when the file isn't
     * parseable. `null` is NOT an error — a malformed `.nfo` returns `null` and
     * the scan continues with the other metadata sources.
     */
    suspend fun parse(file: Path): SidecarMetadata?
}
