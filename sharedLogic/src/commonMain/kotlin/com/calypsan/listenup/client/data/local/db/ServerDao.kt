package com.calypsan.listenup.client.data.local.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.calypsan.listenup.core.currentEpochMilliseconds
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Server entities.
 *
 * Manages multi-server support with per-server authentication.
 * Only one server can be active at a time.
 *
 * Flow for server selection:
 * 1. Discovery finds servers → upsert with localUrl, lastSeenAt
 * 2. User selects server → setActive(serverId)
 * 3. User authenticates → saveAuthTokens(...)
 * 4. User switches server → setActive(otherServerId) - tokens preserved
 */
@Dao
interface ServerDao {
    // ============================================================
    // Insert/Update
    // ============================================================

    /**
     * Insert or update a server.
     * If a server with the same ID exists, it will be updated.
     */
    @Upsert
    suspend fun upsert(server: ServerEntity)

    // ============================================================
    // Query - One-shot
    // ============================================================

    /**
     * Get a server by ID.
     */
    @Query("SELECT * FROM servers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ServerEntity?

    /**
     * Get the currently active server.
     * @return ServerEntity if one is active, null otherwise
     */
    @Query("SELECT * FROM servers WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): ServerEntity?

    /**
     * Get all servers.
     */
    @Query("SELECT * FROM servers ORDER BY lastConnectedAt DESC NULLS LAST")
    suspend fun getAll(): List<ServerEntity>

    // ============================================================
    // Query - Reactive
    // ============================================================

    /**
     * Observe all servers reactively, ordered by most recently connected.
     */
    @Query("SELECT * FROM servers ORDER BY lastConnectedAt DESC NULLS LAST")
    fun observeAll(): Flow<List<ServerEntity>>

    /**
     * Observe the currently active server reactively.
     */
    @Query("SELECT * FROM servers WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<ServerEntity?>

    // ============================================================
    // Server Selection
    // ============================================================

    /**
     * Deactivate all servers.
     * Internal helper - use [setActive] instead.
     */
    @Query("UPDATE servers SET isActive = 0")
    suspend fun deactivateAll()

    /**
     * Activate a specific server.
     * Internal helper - use [setActive] instead.
     */
    @Query("UPDATE servers SET isActive = 1, lastConnectedAt = :timestamp WHERE id = :id")
    suspend fun activate(
        id: String,
        timestamp: Long = currentEpochMilliseconds(),
    )

    /**
     * Set a server as the active server.
     * Deactivates all other servers first.
     *
     * @param serverId The ID of the server to activate
     */
    @Transaction
    suspend fun setActive(serverId: String) {
        deactivateAll()
        activate(serverId)
    }

    // ============================================================
    // Authentication
    // ============================================================

    /**
     * Save authentication tokens for a server.
     * Also updates lastConnectedAt timestamp.
     */
    @Query(
        """
        UPDATE servers SET
            accessToken = :accessToken,
            refreshToken = :refreshToken,
            sessionId = :sessionId,
            userId = :userId,
            lastConnectedAt = :timestamp
        WHERE id = :serverId
        """,
    )
    suspend fun saveAuthTokens(
        serverId: String,
        accessToken: String,
        refreshToken: String,
        sessionId: String,
        userId: String,
        timestamp: Long = currentEpochMilliseconds(),
    )

    /**
     * Update only the access token (for token refresh).
     */
    @Query("UPDATE servers SET accessToken = :accessToken WHERE id = :serverId")
    suspend fun updateAccessToken(
        serverId: String,
        accessToken: String,
    )

    /**
     * Clear authentication tokens for a server.
     * Preserves server in list (soft logout).
     */
    @Query(
        """
        UPDATE servers SET
            accessToken = NULL,
            refreshToken = NULL,
            sessionId = NULL,
            userId = NULL
        WHERE id = :serverId
        """,
    )
    suspend fun clearAuthTokens(serverId: String)

    // ============================================================
    // Discovery Updates
    // ============================================================

    /**
     * Update local URL from mDNS discovery.
     * Called when server is (re)discovered on local network.
     */
    @Query("UPDATE servers SET localUrl = :localUrl, lastSeenAt = :timestamp WHERE id = :id")
    suspend fun updateLocalUrl(
        id: String,
        localUrl: String,
        timestamp: Long = currentEpochMilliseconds(),
    )

    /**
     * Update server metadata from mDNS discovery.
     * Updates name, versions, URLs, and lastSeenAt.
     */
    @Query(
        """
        UPDATE servers SET
            name = :name,
            apiVersion = :apiVersion,
            serverVersion = :serverVersion,
            localUrl = :localUrl,
            remoteUrl = COALESCE(:remoteUrl, remoteUrl),
            lastSeenAt = :timestamp
        WHERE id = :id
        """,
    )
    suspend fun updateFromDiscovery(
        id: String,
        name: String,
        apiVersion: String,
        serverVersion: String,
        localUrl: String,
        remoteUrl: String?,
        timestamp: Long = currentEpochMilliseconds(),
    )

    // ============================================================
    // Delete
    // ============================================================

    /**
     * Delete a server by ID.
     */
    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Clear all servers from the database.
     * Used during full reset.
     */
    @Query("DELETE FROM servers")
    suspend fun clear()
}
