package com.calypsan.listenup.server.scanner.watcher

import kotlinx.coroutines.CoroutineScope

/**
 * Creates the platform's [LowLevelDirectoryWatcher]: the JVM `WatchService`-backed
 * [RecursiveDirectoryWatcher], or the native inotify-backed `InotifyDirectoryWatcher` on linuxX64.
 * The watcher's event loop runs on [scope].
 */
internal expect fun newLowLevelDirectoryWatcher(scope: CoroutineScope): LowLevelDirectoryWatcher
