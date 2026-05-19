package com.calypsan.listenup.client.domain.model

/**
 * Domain model representing a library.
 *
 * A library is a collection of audiobooks managed by a server.
 * Access to books is controlled by the access mode setting.
 *
 * @property id Unique library identifier
 * @property name Display name of the library
 * @property ownerId ID of the library owner
 * @property scanPaths File system paths included in this library
 * @property skipInbox Whether new books skip inbox review
 * @property accessMode Controls default book visibility
 * @property createdAt Creation timestamp as ISO string
 * @property updatedAt Last update timestamp as ISO string
 */
data class Library(
    val id: String,
    val name: String,
    val ownerId: String,
    val scanPaths: List<String>,
    val skipInbox: Boolean,
    val accessMode: AccessMode,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * Library access mode determines default book visibility.
 *
 * This controls whether books are visible by default or require
 * explicit collection membership for access.
 */
enum class AccessMode {
    /**
     * Open mode: uncollected books are visible to all users.
     * Collections restrict access (carve out privacy).
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
