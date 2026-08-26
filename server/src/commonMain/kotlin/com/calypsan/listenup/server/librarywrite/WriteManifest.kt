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
    /**
     * Whether a failure that was **returned to the caller** should stay in the journal for the
     * boot-time resume to retry.
     *
     * The journal cannot otherwise tell two very different situations apart: the process died
     * mid-manifest (nobody was told, resuming is the only way to finish the job) versus an op
     * failed and the typed failure went back to whoever asked (they were told, and they decided
     * what to do next). Replaying the second is the server re-deciding on the user's behalf,
     * possibly months later at the next reboot.
     *
     * Default true, which is right for organize moves: a half-moved book must be finished, and
     * nobody is harmed by finishing it late. Set **false** for destructive manifests — an admin
     * told "the delete failed" who then keeps the book must not have it deleted out from under
     * them by an unrelated restart. A partially-applied delete abandoned this way is still safe:
     * the book row is untouched and the next scan reconciles what is actually on disk.
     */
    val resumeAfterReportedFailure: Boolean = true,
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
     * manual inspection rather than guess. If both are missing, the content is simply gone
     * (external interference) — also fail typed and keep the journal; never fabricate success.
     */
    data class MoveFile(
        val from: Path,
        val to: Path,
    ) : WriteOp

    /**
     * Moves [from] — which lives **outside** every library folder, under the caller's staging
     * root [fromRoot] — to [to] inside one. The uploads path: an arriving file has no home yet,
     * so it is streamed into staging first and only becomes library content here.
     *
     * [MoveFile] cannot express this. Its containment check requires *both* endpoints to resolve
     * inside a library folder, which is exactly what stops a caller moving library content out;
     * relaxing it would cost that guarantee for every mover. So this op carries its own,
     * narrower contract instead: [to] is checked against the live library roots as usual, [from]
     * must resolve strictly inside [fromRoot], and [fromRoot] itself must resolve *outside* every
     * library folder. An ImportFile can therefore only ever bring content **in** — it can neither
     * take anything out nor shuffle files around inside the library behind [MoveFile]'s back.
     *
     * [fromRoot] rides on the op rather than on the broker so a manifest is self-describing: the
     * journal replays it verbatim at boot, and the containment question a crash-resume asks is
     * the same one the first attempt asked.
     *
     * Idempotency rule: identical to [MoveFile] — see its KDoc for the four-way case breakdown.
     */
    data class ImportFile(
        val from: Path,
        val to: Path,
        val fromRoot: Path,
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

    /**
     * Deletes [dir] if — and only if — it is now empty. Idempotency rule: a missing directory
     * means the delete already happened (or the directory was never left behind) — skip. A
     * directory that still has contents (an untracked file a caller's plan didn't know about, or
     * new content that landed mid-move) is left in place rather than force-deleted — cleanup is
     * best-effort and never blocks the rest of the manifest.
     *
     * Best-effort is what makes this the right op for an **ancestor walk**: Delete Book emits one
     * per level above a removed book, deepest first, and the chain stops on its own at the first
     * directory that still holds something. No caller has to predict where to stop.
     *
     * Two refusals stand behind it, both on *resolved* paths: the ordinary containment check every
     * op gets, and — like [DeleteDir] — [dir] must not BE a library folder root. Containment cannot
     * catch that one (a root is trivially inside itself), and an empty library folder is precisely
     * the state in which an ancestor walk comes closest to deleting the library itself.
     */
    data class DeleteDirIfEmpty(
        val dir: Path,
    ) : WriteOp

    /**
     * Deletes [dir] **and everything inside it**, recursively. The most destructive op in the
     * system: it is the only one that removes bytes the caller never enumerated, so a caller that
     * is wrong about which directory it named is wrong about every file in it.
     *
     * Three refusals stand behind it, all checked on *resolved* paths inside
     * [LibraryWriteBroker.applyOp] before a single entry is unlinked:
     * - the ordinary containment check every op gets — [dir] must resolve inside a live library
     *   folder;
     * - [dir] must not BE a library folder root. Containment cannot catch that one (a root is
     *   trivially inside itself), and a book whose `root_rel_path` came out empty would otherwise
     *   erase the entire library;
     * - [dir] must not be a symbolic link. It would resolve inside the library happily enough while
     *   pointing at another book's directory, and recursing through a link is a different operation
     *   from unlinking it.
     *
     * The walk itself never follows a symbolic link either: a link encountered inside [dir] is
     * unlinked, never descended into, so a link planted in a book folder cannot redirect the delete
     * out of the tree.
     *
     * Idempotency rule: a missing directory means the delete already happened — skip. Unlike
     * [DeleteDirIfEmpty] this is not best-effort cleanup: a directory it cannot empty fails the
     * manifest typed and keeps the journal, so an interrupted delete resumes rather than leaving a
     * half-deleted book behind forever.
     */
    data class DeleteDir(
        val dir: Path,
    ) : WriteOp
}

/** The result of a single successful [LibraryWriteBroker.writeFile] — the landed path and its content hash. */
data class WrittenFile(
    val path: Path,
    val contentHashHex: String,
)
