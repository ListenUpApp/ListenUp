package com.calypsan.listenup.server.librarywrite

import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import kotlinx.io.files.Path

/**
 * [LibraryRootProvider] backed by the `library_folders` table.
 *
 * Reads on every call rather than caching: folders are added and removed while the server runs,
 * and a stale allow-list is wrong in both directions — it would refuse writes into a
 * just-added folder, and keep accepting them into a just-removed one. The query is a single
 * indexed scan of a table with a handful of rows, against local SQLite, so the read is far
 * cheaper than the file I/O it is guarding.
 */
class SqlLibraryRootProvider(
    private val db: ListenUpDatabase,
) : LibraryRootProvider {
    override suspend fun roots(): List<Path> =
        db.libraryFoldersQueries
            .selectLiveRootPaths()
            .executeAsList()
            .map { Path(it) }
}
