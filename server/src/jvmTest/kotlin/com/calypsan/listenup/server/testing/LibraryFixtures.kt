package com.calypsan.listenup.server.testing

import com.calypsan.listenup.api.dto.AccessMode
import com.calypsan.listenup.api.dto.Library
import com.calypsan.listenup.api.dto.LibraryFolderRef
import com.calypsan.listenup.core.FolderId
import com.calypsan.listenup.core.LibraryId

/**
 * Builds a [Library] fixture for scanner unit tests.
 *
 * @param id stable library identifier (defaults to "test-lib-1")
 * @param name human-readable name (defaults to "Test Library")
 * @param folders list of absolute folder paths to register under the library
 */
fun testLibrary(
    id: String = "test-lib-1",
    name: String = "Test Library",
    folders: List<String> = listOf("/tmp/test"),
): Library =
    Library(
        id = LibraryId(id),
        name = name,
        folders =
            folders.mapIndexed { i, path ->
                LibraryFolderRef(FolderId("$id-folder-$i"), path)
            },
        metadataPrecedence = "embedded,abs,sidecar",
        accessMode = AccessMode.SHARED,
        createdByUserId = null,
        createdAt = 0L,
    )
