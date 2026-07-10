package com.calypsan.listenup.server.librarywrite

import kotlinx.io.files.Path

/**
 * One journaled multi-file operation for [LibraryWriteBroker.executeManifest]. [ops] execute in
 * order; each is individually idempotent to re-apply (see the KDoc on each [WriteOp] subtype),
 * so a crash mid-manifest can resume from the first un-`done` op without double-applying earlier
 * ones. [opId] is a server-minted id and doubles as the journal filename
 * (`$LISTENUP_HOME/write-journal/<opId>.json`).
 */
data class WriteManifest(
    val opId: String,
    val ops: List<WriteOp>,
)

/** A single filesystem step inside a [WriteManifest]. See each subtype's KDoc for its idempotency rule under crash-resume. */
sealed interface WriteOp {
    /** Ensures [dir] exists. Naturally idempotent — `createDirectories` on an existing directory is a no-op. */
    data class EnsureDir(
        val dir: Path,
    ) : WriteOp

    /**
     * Moves [from] to [to]. Idempotency rule: if [from] is missing and [to] exists, the move
     * already happened — skip. If both exist, the outcome is ambiguous (which one is which
     * generation of content?) — resume must fail the manifest typed and keep the journal for
     * manual inspection rather than guess.
     */
    data class MoveFile(
        val from: Path,
        val to: Path,
    ) : WriteOp

    /**
     * Writes [bytes] to [target] atomically (temp + rename). Idempotency rule: always safe to
     * rewrite unconditionally — the write is atomic and re-writing the same bytes produces the
     * same outcome.
     */
    data class WriteFile(
        val target: Path,
        val bytes: ByteArray,
    ) : WriteOp

    /** Deletes [target]. Idempotency rule: a missing target means the delete already happened — skip. */
    data class DeleteFile(
        val target: Path,
    ) : WriteOp
}

/** The result of a single successful [LibraryWriteBroker.writeFile] — the landed path and its content hash. */
data class WrittenFile(
    val path: Path,
    val contentHashHex: String,
)
