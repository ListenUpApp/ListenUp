package com.calypsan.listenup.server.scanner.watcher

import kotlinx.coroutines.CoroutineScope

internal actual fun newLowLevelDirectoryWatcher(scope: CoroutineScope): LowLevelDirectoryWatcher =
    InotifyDirectoryWatcher(scope)
