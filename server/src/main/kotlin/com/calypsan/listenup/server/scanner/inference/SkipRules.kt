package com.calypsan.listenup.server.scanner.inference

import java.nio.file.Files
import java.nio.file.Path

/**
 * Files and directories the Walker must skip. The rules are sourced from
 * ABS's `server/utils/fileUtils.js:140-166` so an ABS library and a
 * ListenUp library agree on what's invisible.
 *
 * The same predicate is also used by the watcher when deciding whether a
 * filesystem event warrants a re-scan — keeping a single source of truth
 * means a file ABS hides can never accidentally surface in our pipeline.
 */
object SkipRules {
    private val tempExtensions =
        setOf(
            ".part",
            ".tmp",
            ".crdownload",
            ".download",
            ".bak",
            ".old",
            ".temp",
            ".tempfile",
        )

    fun shouldSkip(path: Path): Boolean {
        val name = path.fileName?.toString() ?: return false

        // Dotfiles. ABS skips any path component starting with `.`; we apply the
        // rule per entry so the Walker can prune subtrees as it descends.
        if (name.startsWith(".")) return true

        // Vendor junk — Synology indexer dirs.
        val asString = path.toString()
        if (asString.contains("/@eaDir/") || asString.contains("\\@eaDir\\")) return true

        // Temp / partial-download extensions. Casefold; not all OSes preserve case.
        if (tempExtensions.any { name.endsWith(it, ignoreCase = true) }) return true

        // `.ignore` sentinel — presence of a `.ignore` sibling file marks the
        // containing directory (and everything beneath) as skipped.
        val parent = path.parent
        if (parent != null && Files.exists(parent.resolve(".ignore"))) return true

        return false
    }
}
