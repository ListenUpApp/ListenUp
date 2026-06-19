package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.sync.SyncableTable

/**
 * The `library_folders` table — each row is a root filesystem path within
 * a parent [LibraryTable] row.
 *
 * Extends [SyncableTable] — every row carries `revision`, `created_at`,
 * `updated_at`, `deleted_at`, `client_op_id`. Library folders are
 * server-wide (cross-user), matching their parent library's scope.
 *
 * The unique partial index on [rootPath] (`WHERE deleted_at IS NULL`) prevents
 * the same filesystem path from being registered under two live libraries, while
 * allowing tombstoned rows to coexist for sync history.
 *
 * Cascade semantics: deleting a library soft-deletes its folders (application
 * layer in `LibraryAdminServiceImpl`); the `ON DELETE CASCADE` on the FK provides
 * an additional hard-delete safety net at the DB layer.
 */
internal object LibraryFolderTable : SyncableTable("library_folders") {
    val id = varchar("id", 36)

    /** The parent library this folder belongs to. */
    val libraryId = reference("library_id", LibraryTable.id)

    /** Absolute filesystem path to the root of this folder on the server. */
    val rootPath = varchar("root_path", 1024)

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("idx_library_folders_root_path", rootPath)
        index("idx_library_folders_library_id", false, libraryId)
    }
}
