package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.sync.SyncableTable

/**
 * The `libraries` table — each row represents one user-named library with
 * N folders (see [LibraryFolderTable]).
 *
 * Extends [SyncableTable] — every row carries `revision`, `created_at`,
 * `updated_at`, `deleted_at`, `client_op_id`. Libraries are server-wide
 * (cross-user) in the current single-user model; `access_mode` and
 * `created_by_user_id` are forward-staged for the future Multi-user phase
 * and not enforced today.
 *
 * The old single-bootstrap-row shape with `root_path` was replaced in
 * Flyway V20 (Libraries phase). `LibraryRegistry` is the Books-A relic
 * that resolved the single path; it is superseded by `LibraryRepository`
 * + `LibraryAdminServiceImpl`.
 */
internal object LibraryTable : SyncableTable("libraries") {
    val id = varchar("id", 36)
    val name = varchar("name", 256)

    /**
     * Operator-configured textual-metadata precedence, stored as a
     * comma-separated source list (e.g. `"embedded,abs,sidecar"`).
     * Default matches `MetadataPrecedence.DEFAULT`.
     */
    val metadataPrecedence = varchar("metadata_precedence", 256).default("embedded,abs,sidecar")

    /**
     * Forward-staged multi-user access policy. Default `"shared"` means
     * visible to all authenticated users. Enforcement is deferred to the
     * Multi-user phase.
     */
    val accessMode = varchar("access_mode", 16).default("shared")

    /**
     * Forward-staged owner reference. Null in the current single-user model.
     * Set at creation time in the Multi-user phase.
     */
    val createdByUserId = varchar("created_by_user_id", 36).nullable()

    /**
     * When `true`, a newly-scanned book lands in this library's inbox (hidden
     * from members, visible to admins) pending triage, rather than becoming
     * immediately public. Off by default — sharing is the default, so a library
     * only inboxes new books when an operator explicitly opts in.
     *
     * Not part of [com.calypsan.listenup.api.sync.LibrarySyncPayload] — it's a
     * server-side scanner gate, read directly by the ingest path, not synced to
     * clients. An operator UI to toggle it is a follow-up.
     */
    val inboxEnabled = bool("inbox_enabled").default(false)

    override val primaryKey = PrimaryKey(id)
}
