package com.calypsan.listenup.server.absimport

import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.imports.AbsUserMatch
import com.calypsan.listenup.api.dto.imports.ImportAnalysis
import com.calypsan.listenup.api.dto.imports.ImportStatus
import com.calypsan.listenup.api.dto.imports.ImportSummary
import com.calypsan.listenup.core.AbsItemId
import com.calypsan.listenup.core.AbsUserId
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ImportId
import com.calypsan.listenup.server.io.creationTimeMillis
import com.calypsan.listenup.server.io.deleteRecursively
import com.calypsan.listenup.server.io.fileIoDispatcher
import com.calypsan.listenup.server.io.readText
import com.calypsan.listenup.server.io.writeText
import kotlinx.coroutines.withContext
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Filesystem-truth job state for staged ABS imports.
 *
 * There is no database table for import jobs: the state of an import is entirely determined by the
 * files present in its working directory under [ImportPaths.dirFor]. [ImportStore] is the single
 * place that reads and writes those files — enumerating jobs, deriving their
 * [ImportStatus] from which staging files exist, and persisting/reading the
 * `analysis.json` and `mapping.json` sidecars. It also owns the `.applying`/`.applied`
 * apply markers, whose combination detects an interrupted apply (see [hasInterruptedApply]).
 *
 * All file I/O runs on [fileIoDispatcher].
 */
class ImportStore(
    private val paths: ImportPaths,
) {
    /** Lists every staged import, newest first, deriving status and counts from the filesystem. */
    suspend fun listImports(): List<ImportSummary> =
        onIo {
            val root = paths.importsDir
            if (!SystemFileSystem.exists(root)) return@onIo emptyList()
            SystemFileSystem
                .list(root)
                .filter { entry ->
                    SystemFileSystem.metadataOrNull(entry)?.isDirectory == true && entry.name != paths.tmpDir.name
                }.mapNotNull { dir -> summaryFor(dir.name) }
                .sortedByDescending { it.createdAt }
        }

    /** Returns the summary for [id], or null if no such import directory exists. */
    suspend fun getImport(id: ImportId): ImportSummary? = onIo { summaryFor(id.value) }

    /** Recursively deletes the working directory for [id]. Returns false if it did not exist. */
    suspend fun deleteImport(id: ImportId): Boolean =
        onIo {
            val dir = paths.dirFor(id.value)
            if (!SystemFileSystem.exists(dir)) {
                false
            } else {
                // Preserve the old File.deleteRecursively() contract: false on any delete failure, not a throw.
                runCatching { deleteRecursively(dir) }.isSuccess
            }
        }

    /** Persists the analysis preview for [id] as `analysis.json`. */
    suspend fun writeAnalysis(
        id: ImportId,
        analysis: ImportAnalysis,
    ) = onIo {
        paths.analysisFor(id.value).writeText(json.encodeToString(analysis))
    }

    /**
     * Persists the server-internal resolved matches for [id] as `matches.json`.
     *
     * The contract [ImportAnalysis] is a lossy projection (counts + ambiguous/unmatched refs only),
     * so apply can't reconstruct the per-item book resolution from it. [ResolvedImport] is the full
     * `absItemId → matched BookId` map plus the user matches, written alongside `analysis.json` so
     * apply reads it back instead of re-running matching.
     */
    suspend fun writeMatches(
        id: ImportId,
        matches: ResolvedImport,
    ) = onIo {
        paths.matchesFor(id.value).writeText(json.encodeToString(matches))
    }

    /** Reads the persisted resolved matches for [id], or null if not yet analyzed. */
    suspend fun readMatches(id: ImportId): ResolvedImport? =
        onIo {
            val file = paths.matchesFor(id.value)
            if (SystemFileSystem.exists(file)) json.decodeFromString<ResolvedImport>(file.readText()) else null
        }

    /** Persists the confirmed mapping for [id] as `mapping.json`. */
    suspend fun writeMapping(
        id: ImportId,
        userMappings: Map<AbsUserId, UserId>,
        bookOverrides: Map<AbsItemId, BookId?>,
    ) = onIo {
        paths.mappingFor(id.value).writeText(json.encodeToString(StoredMapping(userMappings, bookOverrides)))
    }

    /** Reads the persisted mapping for [id], or null if no mapping has been confirmed. */
    suspend fun readMapping(id: ImportId): StoredMapping? =
        onIo {
            val file = paths.mappingFor(id.value)
            if (SystemFileSystem.exists(file)) json.decodeFromString<StoredMapping>(file.readText()) else null
        }

    /**
     * Touches the `.applying` marker, recording that apply has started writing rows for [id]. A
     * marker that persists without `.applied` means an apply was interrupted (crash or failure)
     * after possibly committing partial rows — see [hasInterruptedApply] and
     * [InterruptedImportResumer].
     */
    suspend fun markApplying(id: ImportId) =
        onIo {
            paths.applyingMarkerFor(id.value).writeText("")
        }

    /**
     * Touches the `.applied` marker, recording that apply has completed for [id], and clears the
     * in-flight `.applying` marker so a completed import can never look interrupted.
     */
    suspend fun markApplied(id: ImportId) =
        onIo {
            paths.appliedMarkerFor(id.value).writeText("")
            SystemFileSystem.delete(paths.applyingMarkerFor(id.value), mustExist = false)
        }

    /**
     * True when an apply started for [id] but never completed: the `.applying` marker exists and
     * `.applied` does not. Partial rows may be committed and per-user stats may be stale until the
     * apply is re-run (idempotent).
     */
    suspend fun hasInterruptedApply(id: ImportId): Boolean =
        onIo {
            SystemFileSystem.exists(paths.applyingMarkerFor(id.value)) &&
                !SystemFileSystem.exists(paths.appliedMarkerFor(id.value))
        }

    /** Every staged import whose apply was interrupted (see [hasInterruptedApply]). */
    suspend fun listInterruptedApplies(): List<ImportId> =
        onIo {
            val root = paths.importsDir
            if (!SystemFileSystem.exists(root)) return@onIo emptyList()
            SystemFileSystem
                .list(root)
                .filter { entry ->
                    SystemFileSystem.metadataOrNull(entry)?.isDirectory == true && entry.name != paths.tmpDir.name
                }.map { ImportId(it.name) }
                .filter { id ->
                    SystemFileSystem.exists(paths.applyingMarkerFor(id.value)) &&
                        !SystemFileSystem.exists(paths.appliedMarkerFor(id.value))
                }
        }

    /** Derives the lifecycle status of [id] from which staging files exist. */
    fun statusOf(id: ImportId): ImportStatus = statusOf(id.value)

    private fun statusOf(id: String): ImportStatus =
        when {
            SystemFileSystem.exists(paths.appliedMarkerFor(id)) -> ImportStatus.APPLIED
            SystemFileSystem.exists(paths.mappingFor(id)) -> ImportStatus.MAPPED
            SystemFileSystem.exists(paths.analysisFor(id)) -> ImportStatus.ANALYZED
            else -> ImportStatus.UPLOADED
        }

    /** Builds the summary for a single import directory, or null if the directory is absent. */
    private fun summaryFor(id: String): ImportSummary? {
        val dir = paths.dirFor(id)
        if (!SystemFileSystem.exists(dir)) return null
        val analysis = readAnalysisBlocking(id)
        return ImportSummary(
            id = ImportId(id),
            createdAt = createdAtFor(id),
            status = statusOf(id),
            bookCount = analysis?.let(::bookCountOf) ?: 0,
            userCount = analysis?.userMatches?.size ?: 0,
        )
    }

    private fun readAnalysisBlocking(id: String): ImportAnalysis? {
        val file = paths.analysisFor(id)
        return if (SystemFileSystem.exists(file)) json.decodeFromString<ImportAnalysis>(file.readText()) else null
    }

    /** Reads `createdAt` from the upload-time `meta.json`, falling back to the dir creation time. */
    private fun createdAtFor(id: String): Long {
        val meta = paths.metaFor(id)
        if (SystemFileSystem.exists(meta)) {
            runCatching { json.decodeFromString<ImportMeta>(meta.readText()).createdAt }
                .getOrNull()
                ?.let { return it }
        }
        return runCatching { creationTimeMillis(paths.dirFor(id)) }.getOrDefault(0L)
    }

    /** Total books surfaced in the preview: matched (definitive tiers) + ambiguous + unmatched. */
    private fun bookCountOf(analysis: ImportAnalysis): Int {
        val definitive =
            analysis.bookMatchCounts
                .filterKeys { it.isDefinitiveBookTier() }
                .values
                .sum()
        return definitive + analysis.ambiguous.size + analysis.unmatched.size
    }

    private suspend fun <T> onIo(block: () -> T): T = withContext(fileIoDispatcher) { block() }

    /** Server-internal persisted shape of a confirmed mapping (`mapping.json`). */
    @Serializable
    data class StoredMapping(
        @SerialName("userMappings")
        val userMappings: Map<AbsUserId, UserId>,
        @SerialName("bookOverrides")
        val bookOverrides: Map<AbsItemId, BookId?>,
    )

    /**
     * Server-internal persisted resolution produced by analyze (`matches.json`).
     *
     * [itemMatches] maps each ABS item that resolved to exactly one ListenUp book; ambiguous and
     * unmatched items are deliberately absent (never auto-resolved). [userMatches] mirrors the
     * suggested user mapping so apply can validate without re-running the user matcher. Progress rows
     * are *not* persisted here — apply re-reads them from the ABS database directly.
     */
    @Serializable
    data class ResolvedImport(
        @SerialName("itemMatches")
        val itemMatches: Map<AbsItemId, BookId>,
        @SerialName("userMatches")
        val userMatches: List<AbsUserMatch>,
    )

    /** Server-internal upload-time metadata sidecar (`meta.json`). */
    @Serializable
    private data class ImportMeta(
        @SerialName("createdAt")
        val createdAt: Long,
    )

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** The book-match tiers that represent a confident single-book match (not ambiguous/unmatched). */
private fun com.calypsan.listenup.api.dto.imports.MatchTier.isDefinitiveBookTier(): Boolean =
    when (this) {
        com.calypsan.listenup.api.dto.imports.MatchTier.ASIN,
        com.calypsan.listenup.api.dto.imports.MatchTier.ISBN,
        com.calypsan.listenup.api.dto.imports.MatchTier.PATH,
        com.calypsan.listenup.api.dto.imports.MatchTier.TITLE_AUTHOR,
        -> true

        else -> false
    }
