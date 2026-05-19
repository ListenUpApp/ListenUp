package com.calypsan.listenup.client.data.local.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ServerEntity].
 *
 * Tests the helper methods for authentication status, URL freshness,
 * and URL selection logic.
 */
class ServerEntityTest {
    // ============================================================
    // Test Data Factory
    // ============================================================

    private fun createServer(
        id: String = "server-1",
        name: String = "Test Server",
        apiVersion: String = "v1",
        serverVersion: String = "1.0.0",
        localUrl: String? = "http://192.168.1.100:8080",
        remoteUrl: String? = null,
        accessToken: String? = null,
        refreshToken: String? = null,
        sessionId: String? = null,
        userId: String? = null,
        isActive: Boolean = false,
        lastSeenAt: Long = 0,
        lastConnectedAt: Long? = null,
    ) = ServerEntity(
        id = id,
        name = name,
        apiVersion = apiVersion,
        serverVersion = serverVersion,
        localUrl = localUrl,
        remoteUrl = remoteUrl,
        accessToken = accessToken,
        refreshToken = refreshToken,
        sessionId = sessionId,
        userId = userId,
        isActive = isActive,
        lastSeenAt = lastSeenAt,
        lastConnectedAt = lastConnectedAt,
    )

    // ============================================================
    // isAuthenticated Tests
    // ============================================================

    @Test
    fun `isAuthenticated returns false when no tokens`() {
        val server = createServer()

        assertFalse(server.isAuthenticated())
    }

    @Test
    fun `isAuthenticated returns false when only accessToken`() {
        val server = createServer(accessToken = "access-token")

        assertFalse(server.isAuthenticated())
    }

    @Test
    fun `isAuthenticated returns false when only refreshToken`() {
        val server = createServer(refreshToken = "refresh-token")

        assertFalse(server.isAuthenticated())
    }

    @Test
    fun `isAuthenticated returns false when tokens but no userId`() {
        val server =
            createServer(
                accessToken = "access-token",
                refreshToken = "refresh-token",
            )

        assertFalse(server.isAuthenticated())
    }

    @Test
    fun `isAuthenticated returns true when all auth fields present`() {
        val server =
            createServer(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                sessionId = "session-id",
                userId = "user-id",
            )

        assertTrue(server.isAuthenticated())
    }

    // ============================================================
    // isLocalUrlFresh Tests
    // ============================================================

    @Test
    fun `isLocalUrlFresh returns false when no localUrl`() {
        val server = createServer(localUrl = null, lastSeenAt = currentTime())

        assertFalse(server.isLocalUrlFresh())
    }

    @Test
    fun `isLocalUrlFresh returns false when lastSeenAt is zero`() {
        val server = createServer(localUrl = "http://192.168.1.100:8080", lastSeenAt = 0)

        assertFalse(server.isLocalUrlFresh())
    }

    @Test
    fun `isLocalUrlFresh returns false when lastSeenAt is stale`() {
        // Seen 10 minutes ago (threshold is 5 minutes)
        val tenMinutesAgo = currentTime() - (10 * 60 * 1000)
        val server =
            createServer(
                localUrl = "http://192.168.1.100:8080",
                lastSeenAt = tenMinutesAgo,
            )

        assertFalse(server.isLocalUrlFresh())
    }

    @Test
    fun `isLocalUrlFresh returns true when lastSeenAt is recent`() {
        // Seen 1 minute ago
        val oneMinuteAgo = currentTime() - (1 * 60 * 1000)
        val server =
            createServer(
                localUrl = "http://192.168.1.100:8080",
                lastSeenAt = oneMinuteAgo,
            )

        assertTrue(server.isLocalUrlFresh())
    }

    @Test
    fun `isLocalUrlFresh respects custom threshold`() {
        // Seen 3 minutes ago
        val threeMinutesAgo = currentTime() - (3 * 60 * 1000)
        val server =
            createServer(
                localUrl = "http://192.168.1.100:8080",
                lastSeenAt = threeMinutesAgo,
            )

        // With 2 minute threshold, should be stale
        assertFalse(server.isLocalUrlFresh(staleThresholdMs = 2 * 60 * 1000))

        // With 5 minute threshold, should be fresh
        assertTrue(server.isLocalUrlFresh(staleThresholdMs = 5 * 60 * 1000))
    }

    // ============================================================
    // getBestUrl Tests
    // ============================================================

    @Test
    fun `getBestUrl returns null when no URLs configured`() {
        val server = createServer(localUrl = null, remoteUrl = null)

        assertNull(server.getBestUrl())
    }

    @Test
    fun `getBestUrl returns fresh localUrl when available`() {
        val recentTime = currentTime() - (1 * 60 * 1000)
        val server =
            createServer(
                localUrl = "http://192.168.1.100:8080",
                remoteUrl = "https://example.com",
                lastSeenAt = recentTime,
            )

        assertEquals("http://192.168.1.100:8080", server.getBestUrl())
    }

    @Test
    fun `getBestUrl returns remoteUrl when localUrl is stale`() {
        val tenMinutesAgo = currentTime() - (10 * 60 * 1000)
        val server =
            createServer(
                localUrl = "http://192.168.1.100:8080",
                remoteUrl = "https://example.com",
                lastSeenAt = tenMinutesAgo,
            )

        assertEquals("https://example.com", server.getBestUrl())
    }

    @Test
    fun `getBestUrl returns remoteUrl when no localUrl`() {
        val server =
            createServer(
                localUrl = null,
                remoteUrl = "https://example.com",
            )

        assertEquals("https://example.com", server.getBestUrl())
    }

    @Test
    fun `getBestUrl returns stale localUrl when no remoteUrl`() {
        val tenMinutesAgo = currentTime() - (10 * 60 * 1000)
        val server =
            createServer(
                localUrl = "http://192.168.1.100:8080",
                remoteUrl = null,
                lastSeenAt = tenMinutesAgo,
            )

        // Stale local URL is better than nothing
        assertEquals("http://192.168.1.100:8080", server.getBestUrl())
    }

    @Test
    fun `getBestUrl respects custom threshold`() {
        // Seen 3 minutes ago
        val threeMinutesAgo = currentTime() - (3 * 60 * 1000)
        val server =
            createServer(
                localUrl = "http://192.168.1.100:8080",
                remoteUrl = "https://example.com",
                lastSeenAt = threeMinutesAgo,
            )

        // With 2 minute threshold, localUrl is stale -> use remote
        assertEquals("https://example.com", server.getBestUrl(staleThresholdMs = 2 * 60 * 1000))

        // With 5 minute threshold, localUrl is fresh -> use local
        assertEquals("http://192.168.1.100:8080", server.getBestUrl(staleThresholdMs = 5 * 60 * 1000))
    }

    // ============================================================
    // Constant Tests
    // ============================================================

    @Test
    fun `STALE_THRESHOLD_MS is 5 minutes`() {
        assertEquals(5 * 60 * 1000L, ServerEntity.STALE_THRESHOLD_MS)
    }

    // ============================================================
    // Helper
    // ============================================================

    private fun currentTime(): Long =
        com.calypsan.listenup.core
            .currentEpochMilliseconds()
}
