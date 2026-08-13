package com.calypsan.listenup.server.testing

import com.calypsan.listenup.api.dto.AccessMode
import com.calypsan.listenup.api.dto.Library
import com.calypsan.listenup.api.dto.LibraryFolderRef
import com.calypsan.listenup.api.sync.BookAudioFilePayload
import com.calypsan.listenup.api.sync.BookSyncPayload
import com.calypsan.listenup.api.sync.CoverPayload
import com.calypsan.listenup.api.sync.CoverSource
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

/**
 * A book whose cover is a file on disk at `books/<id>/`, carrying an explicit [hash] so a cover
 * route has a stable value to fold into its `ETag`. Seed it with [com.calypsan.listenup.server
 * .services.BookRepository.upsert] after writing the image file itself.
 */
fun filesystemCoverBook(
    id: String,
    hash: String,
): BookSyncPayload =
    BookSyncPayload(
        id = id,
        libraryId = LibraryId("test-library"),
        folderId = FolderId("test-folder"),
        title = "Book $id",
        sortTitle = "Book $id",
        subtitle = null,
        description = null,
        publishYear = null,
        publisher = null,
        language = null,
        isbn = null,
        asin = null,
        abridged = false,
        explicit = false,
        totalDuration = 3_600_000L,
        cover = CoverPayload(source = CoverSource.FILESYSTEM, hash = hash),
        rootRelPath = "books/$id",
        inode = null,
        scannedAt = 1_730_000_000_000L,
        contributors = emptyList(),
        series = emptyList(),
        audioFiles =
            listOf(
                BookAudioFilePayload(
                    id = "af-$id",
                    index = 0,
                    filename = "01.m4b",
                    format = "m4b",
                    codec = "",
                    duration = 3_600_000L,
                    size = 500_000_000L,
                ),
            ),
        chapters = emptyList(),
        revision = 0L,
        updatedAt = 0L,
        createdAt = 0L,
        deletedAt = null,
    )
