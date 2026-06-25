package com.calypsan.listenup.server.scanner

import com.calypsan.listenup.api.dto.LibraryFolderRef
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId
import com.calypsan.listenup.server.scanner.watcher.WatcherSupervisor
import kotlinx.io.files.Path

/** Adapts the concrete jvmMain [WatcherSupervisor] to the commonMain [WatcherSupervisorPort], bridging java.nio→kotlinx.io Path at the watcher hop. */
internal fun WatcherSupervisor.asPort(): WatcherSupervisorPort =
    object : WatcherSupervisorPort {
        override suspend fun mount(
            libraryId: LibraryId,
            folder: LibraryFolderRef,
            onEvent: suspend (LibraryId, Path) -> Unit, // kotlinx.io Path
        ) = this@asPort.mount(libraryId, folder) { libId, nioPath ->
            // nioPath: java.nio Path
            onEvent(libId, Path(nioPath.toString())) // bridge
        }

        override suspend fun unmount(folderId: FolderId) = this@asPort.unmount(folderId)

        override suspend fun unmountAllForLibrary(libraryId: LibraryId) = this@asPort.unmountAllForLibrary(libraryId)

        override suspend fun unmountAll() = this@asPort.unmountAll()
    }
