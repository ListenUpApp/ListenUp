package com.calypsan.listenup.client.domain.model

/**
 * Domain model representing a library.
 *
 * A library is a named, operator-configured collection of zero-or-more
 * [LibraryFolderRef] roots. Libraries are server-wide (cross-user) in the
 * current single-user model.
 *
 * `metadataPrecedence` governs which metadata source wins when multiple
 * sources exist for the same field (e.g. `"embedded,abs,sidecar"`).
 *
 * `createdByUserId` is forward-staged for the multi-user phase; it is
 * present today but not enforced client-side.
 *
 * @property id Unique library identifier.
 * @property name Display name of the library.
 * @property metadataPrecedence Comma-separated source list for metadata resolution.
 * @property accessMode Controls default book visibility.
 * @property createdByUserId User ID of the library creator, null until multi-user enforcement.
 * @property createdAt Creation timestamp as Unix epoch milliseconds.
 * @property revision Monotonic server revision, advanced on every committed change.
 * @property folders Lightweight refs to every root folder registered under this library.
 *   Empty for projections that do not carry folder data; admins see each folder's
 *   [LibraryFolderRef.rootPath].
 */
data class Library(
    val id: String,
    val name: String,
    val folders: List<LibraryFolderRef> = emptyList(),
    val metadataPrecedence: String,
    val accessMode: AccessMode,
    val createdByUserId: String?,
    val createdAt: Long,
    val revision: Long,
)

/**
 * Lightweight reference to a library root folder.
 *
 * Mirrors the contract `LibraryFolderRef`: carries folder identity plus the
 * absolute server filesystem path the scanner walks. [rootPath] is `null` when
 * redacted for a non-admin caller, so members carry folder identity without the
 * server's directory layout.
 *
 * @property id Stable folder identifier.
 * @property rootPath Absolute server path, or `null` when redacted for a non-admin.
 */
data class LibraryFolderRef(
    val id: String,
    val rootPath: String?,
)

/**
 * Library access mode determines default book visibility.
 *
 * This controls whether books are visible by default or require
 * explicit collection membership for access.
 */
enum class AccessMode {
    /**
     * Open mode: newly added books are public by default — they join the shared `ALL_BOOKS`
     * collection that every member can see. Collections carve out privacy.
     */
    OPEN,

    /**
     * Restricted mode: users only see books they're explicitly granted.
     * Collections grant access (opt in).
     */
    RESTRICTED,

    ;

    companion object {
        /**
         * Parse access mode from server string representation.
         * Defaults to OPEN for unknown values.
         */
        fun fromString(value: String): AccessMode = if (value.lowercase() == "restricted") RESTRICTED else OPEN
    }

    /**
     * Convert to API string representation.
     */
    fun toApiString(): String =
        when (this) {
            OPEN -> "open"
            RESTRICTED -> "restricted"
        }
}
