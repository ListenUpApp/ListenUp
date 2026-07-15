package com.calypsan.listenup.server.scanner.inference

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Files and directories the Walker must skip. The rules are sourced from
 * ABS's `server/utils/fileUtils.js:140-166` so an ABS library and a
 * ListenUp library agree on what's invisible.
 *
 * The same predicate is also used by the watcher when deciding whether a
 * filesystem event warrants a re-scan — keeping a single source of truth
 * means a file ABS hides can never accidentally surface in our pipeline.
 */
internal object SkipRules {
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

    /**
     * Name-only skip decision — no filesystem probe. Covers dotfiles, Synology `@eaDir` junk, and
     * temp/partial-download extensions. Safe to call once per walked entry: the Walker owns the
     * (expensive) `.ignore` sentinel probe and does it once per directory via [hasIgnoreSentinel].
     */
    fun shouldSkipByName(path: Path): Boolean {
        val name = path.name
        if (name.isEmpty()) return false

        // Dotfiles. ABS skips any path component starting with `.`; we apply the
        // rule per entry so the Walker can prune subtrees as it descends.
        if (name.startsWith(".")) return true

        // Vendor junk — Synology indexer dirs. The substring form catches files nested inside an
        // `@eaDir`; the name check prunes the `@eaDir` directory itself so we never descend into it.
        if (name == "@eaDir") return true
        val asString = path.toString()
        if (asString.contains("/@eaDir/") || asString.contains("\\@eaDir\\")) return true

        // Temp / partial-download extensions. Casefold; not all OSes preserve case.
        if (tempExtensions.any { name.endsWith(it, ignoreCase = true) }) return true

        return false
    }

    /**
     * True when [dir] carries a `.ignore` sentinel file, marking it (and everything beneath) as
     * skipped. One filesystem probe per directory — the Walker calls this once as it enters a
     * directory instead of once per child entry.
     */
    fun hasIgnoreSentinel(dir: Path): Boolean = SystemFileSystem.exists(Path(dir, ".ignore"))

    /**
     * Full skip decision including the `.ignore` sentinel probe, for low-volume callers (the
     * watcher, one event at a time) that lack a directory-scoped descent to cache the probe in.
     */
    fun shouldSkip(path: Path): Boolean {
        if (shouldSkipByName(path)) return true
        val parent = path.parent
        return parent != null && hasIgnoreSentinel(parent)
    }
}
