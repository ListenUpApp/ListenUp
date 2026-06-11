package com.calypsan.listenup.server.scanner.watcher

import kotlinx.coroutines.flow.Flow

/** A filesystem change under a watched directory, normalized across platforms. */
internal data class DirectoryWatchEvent(
    val targetDirectory: String,
    val path: String,
    val kind: DirectoryWatchEventKind,
)

/** The three change kinds the watcher surfaces (platform events collapse to these). */
internal enum class DirectoryWatchEventKind { Create, Modify, Delete }

/**
 * Minimal directory-watch seam that [FolderWatcher] depends on, decoupling it
 * from any concrete watcher engine.
 *
 * Replaces kfswatch's `KfsDirectoryWatcher`, whose hard `FileWatcherMaxTargets = 63`
 * cap silently dropped every `add()` past the 63rd directory — so on a large
 * library only the first 63 directories were ever watched.
 */
internal interface LowLevelDirectoryWatcher {
    /** Hot stream of changes across every added directory. */
    val onEventFlow: Flow<DirectoryWatchEvent>

    /** Begins watching [directory] (non-recursive; callers add each directory). */
    suspend fun add(directory: String)

    /** Releases the underlying OS handle and stops the event loop. */
    suspend fun close()
}
