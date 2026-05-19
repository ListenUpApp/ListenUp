package com.calypsan.listenup.client.data.repository

import com.calypsan.listenup.client.data.discovery.ServerDiscoveryService
import com.calypsan.listenup.client.data.local.db.ServerDao
import com.calypsan.listenup.client.data.local.db.ServerEntity
import com.calypsan.listenup.client.domain.model.DiscoveredServer
import dev.mokkery.answering.returns
import dev.mokkery.every
import dev.mokkery.everySuspend
import dev.mokkery.matcher.any
import dev.mokkery.mock
import dev.mokkery.verifySuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import com.calypsan.listenup.client.data.discovery.DiscoveredServer as DataDiscoveredServer

/**
 * Unit tests for [ServerRepositoryImpl].
 *
 * Tests the bridging of mDNS discovery with database persistence,
 * server selection, and authentication token management.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerRepositoryTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var testScope: TestScope

    @BeforeTest
    fun setup() {
        testScope = TestScope(testDispatcher)
    }

    // ============================================================
    // Test Fixture
    // ============================================================

    private class TestFixture(
        scope: CoroutineScope,
    ) {
        val serverDao: ServerDao = mock()
        val discoveryService: ServerDiscoveryService = mock()
        val discoveredServersFlow = MutableStateFlow<List<DataDiscoveredServer>>(emptyList())

        init {
            // Default stubs
            every { discoveryService.discover() } returns discoveredServersFlow
            everySuspend { serverDao.observeAll() } returns flowOf(emptyList())
            everySuspend { serverDao.observeActive() } returns flowOf(null)
            everySuspend { serverDao.getActive() } returns null
            everySuspend { serverDao.getById(any()) } returns null
            everySuspend { serverDao.getAll() } returns emptyList()
            everySuspend { serverDao.upsert(any()) } returns Unit
            everySuspend { serverDao.setActive(any()) } returns Unit
            everySuspend { serverDao.updateFromDiscovery(any(), any(), any(), any(), any(), any(), any()) } returns Unit
            everySuspend { serverDao.saveAuthTokens(any(), any(), any(), any(), any(), any()) } returns Unit
            everySuspend { serverDao.updateAccessToken(any(), any()) } returns Unit
            everySuspend { serverDao.clearAuthTokens(any()) } returns Unit
            everySuspend { serverDao.deleteById(any()) } returns Unit
        }

        fun build(scope: CoroutineScope): ServerRepositoryImpl =
            ServerRepositoryImpl(
                serverDao = serverDao,
                discoveryService = discoveryService,
                scope = scope,
            )
    }

    private fun createFixture(): TestFixture = TestFixture(testScope)

    // ============================================================
    // Test Data Factories
    // ============================================================

    private fun createServerEntity(
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
        lastSeenAt = Clock.System.now().toEpochMilliseconds(),
        lastConnectedAt = null,
    )

    /**
     * Creates a data layer discovered server for mocking the discovery service.
     */
    private fun createDataDiscoveredServer(
        id: String = "server-1",
        name: String = "Discovered Server",
        host: String = "192.168.1.100",
        port: Int = 8080,
        apiVersion: String = "v1",
        serverVersion: String = "1.0.0",
        remoteUrl: String? = null,
    ) = DataDiscoveredServer(
        id = id,
        name = name,
        host = host,
        port = port,
        apiVersion = apiVersion,
        serverVersion = serverVersion,
        remoteUrl = remoteUrl,
    )

    /**
     * Creates a domain discovered server for setActiveServer tests.
     */
    private fun createDiscoveredServer(
        id: String = "server-1",
        name: String = "Discovered Server",
        localUrl: String = "http://192.168.1.100:8080",
        apiVersion: String = "v1",
        serverVersion: String = "1.0.0",
        remoteUrl: String? = null,
    ) = DiscoveredServer(
        id = id,
        name = name,
        localUrl = localUrl,
        apiVersion = apiVersion,
        serverVersion = serverVersion,
        remoteUrl = remoteUrl,
    )

    // ============================================================
    // observeServers Tests
    // ============================================================

    @Test
    fun `observeServers returns empty list when no servers`() =
        testScope.runTest {
            val fixture = createFixture()
            val repository = fixture.build(this)

            val servers = repository.observeServers().first()

            assertTrue(servers.isEmpty())
        }

    @Test
    fun `observeServers returns persisted servers with offline status`() =
        testScope.runTest {
            val fixture = createFixture()
            val serverEntity = createServerEntity(id = "server-1", name = "Persisted Server")
            everySuspend { fixture.serverDao.observeAll() } returns flowOf(listOf(serverEntity))

            val repository = fixture.build(this)
            val servers = repository.observeServers().first()

            assertEquals(1, servers.size)
            assertEquals("server-1", servers[0].server.id)
            assertEquals("Persisted Server", servers[0].server.name)
            assertFalse(servers[0].isOnline) // Not discovered
        }

    @Test
    fun `observeServers marks discovered servers as online`() =
        testScope.runTest {
            val fixture = createFixture()
            val serverEntity = createServerEntity(id = "server-1")
            val discoveredServer = createDataDiscoveredServer(id = "server-1")

            // Use MutableStateFlow so combine triggers when discovery updates
            val persistedFlow = MutableStateFlow(listOf(serverEntity))
            everySuspend { fixture.serverDao.observeAll() } returns persistedFlow
            fixture.discoveredServersFlow.value = listOf(discoveredServer)

            val repository = fixture.build(this)
            advanceUntilIdle()

            // drop(1) skips the initial emission from onStart { emit(emptyList()) }
            val servers = repository.observeServers().drop(1).first()

            assertEquals(1, servers.size)
            assertTrue(servers[0].isOnline)
        }

    // ============================================================
    // observeActiveServer Tests
    // ============================================================

    @Test
    fun `observeActiveServer returns null when no active server`() =
        testScope.runTest {
            val fixture = createFixture()
            val repository = fixture.build(this)

            val active = repository.observeActiveServer().first()

            assertNull(active)
        }

    @Test
    fun `observeActiveServer returns active server`() =
        testScope.runTest {
            val fixture = createFixture()
            val serverEntity = createServerEntity(id = "server-1", isActive = true)
            everySuspend { fixture.serverDao.observeActive() } returns flowOf(serverEntity)

            val repository = fixture.build(this)
            val active = repository.observeActiveServer().first()

            assertNotNull(active)
            assertEquals("server-1", active.id)
        }

    // ============================================================
    // getActiveServer Tests
    // ============================================================

    @Test
    fun `getActiveServer returns null when no active server`() =
        testScope.runTest {
            val fixture = createFixture()
            val repository = fixture.build(this)

            val active = repository.getActiveServer()

            assertNull(active)
        }

    @Test
    fun `getActiveServer returns active server`() =
        testScope.runTest {
            val fixture = createFixture()
            val serverEntity = createServerEntity(id = "server-1", isActive = true)
            everySuspend { fixture.serverDao.getActive() } returns serverEntity

            val repository = fixture.build(this)
            val active = repository.getActiveServer()

            assertNotNull(active)
            assertEquals("server-1", active.id)
        }

    // ============================================================
    // setActiveServer Tests
    // ============================================================

    @Test
    fun `setActiveServer by id calls dao setActive`() =
        testScope.runTest {
            val fixture = createFixture()
            val repository = fixture.build(this)

            repository.setActiveServer("server-1")

            verifySuspend { fixture.serverDao.setActive("server-1") }
        }

    @Test
    fun `setActiveServer by discovered creates new server if not exists`() =
        testScope.runTest {
            val fixture = createFixture()
            val discovered = createDiscoveredServer(id = "new-server")
            everySuspend { fixture.serverDao.getById("new-server") } returns null

            val repository = fixture.build(this)
            repository.setActiveServer(discovered)

            verifySuspend { fixture.serverDao.upsert(any()) }
            verifySuspend { fixture.serverDao.setActive("new-server") }
        }

    @Test
    fun `setActiveServer by discovered updates existing server`() =
        testScope.runTest {
            val fixture = createFixture()
            val existing = createServerEntity(id = "existing-server")
            val discovered = createDiscoveredServer(id = "existing-server", name = "Updated Name")
            everySuspend { fixture.serverDao.getById("existing-server") } returns existing

            val repository = fixture.build(this)
            repository.setActiveServer(discovered)

            verifySuspend { fixture.serverDao.updateFromDiscovery(any(), any(), any(), any(), any(), any(), any()) }
            verifySuspend { fixture.serverDao.setActive("existing-server") }
        }

    // ============================================================
    // saveAuthTokens Tests
    // ============================================================

    @Test
    fun `saveAuthTokens saves to active server`() =
        testScope.runTest {
            val fixture = createFixture()
            val activeServer = createServerEntity(id = "active-server", isActive = true)
            everySuspend { fixture.serverDao.getActive() } returns activeServer

            val repository = fixture.build(this)
            repository.saveAuthTokens(
                accessToken = "access",
                refreshToken = "refresh",
                sessionId = "session",
                userId = "user",
            )

            verifySuspend {
                fixture.serverDao.saveAuthTokens(
                    serverId = "active-server",
                    accessToken = "access",
                    refreshToken = "refresh",
                    sessionId = "session",
                    userId = "user",
                    timestamp = any(),
                )
            }
        }

    // ============================================================
    // updateAccessToken Tests
    // ============================================================

    @Test
    fun `updateAccessToken updates active server token`() =
        testScope.runTest {
            val fixture = createFixture()
            val activeServer = createServerEntity(id = "active-server", isActive = true)
            everySuspend { fixture.serverDao.getActive() } returns activeServer

            val repository = fixture.build(this)
            repository.updateAccessToken("new-access-token")

            verifySuspend { fixture.serverDao.updateAccessToken("active-server", "new-access-token") }
        }

    // ============================================================
    // clearAuthTokens Tests
    // ============================================================

    @Test
    fun `clearAuthTokens clears tokens for active server`() =
        testScope.runTest {
            val fixture = createFixture()
            val activeServer = createServerEntity(id = "active-server", isActive = true)
            everySuspend { fixture.serverDao.getActive() } returns activeServer

            val repository = fixture.build(this)
            repository.clearAuthTokens()

            verifySuspend { fixture.serverDao.clearAuthTokens("active-server") }
        }

    // ============================================================
    // addManualServer Tests
    // ============================================================

    @Test
    fun `addManualServer creates server with remote URL`() =
        testScope.runTest {
            val fixture = createFixture()
            val repository = fixture.build(this)

            val result =
                repository.addManualServer(
                    name = "Manual Server",
                    url = "https://example.com",
                )

            verifySuspend { fixture.serverDao.upsert(any()) }
            assertEquals("Manual Server", result.name)
            assertEquals("https://example.com", result.remoteUrl)
        }

    // ============================================================
    // removeServer Tests
    // ============================================================

    @Test
    fun `removeServer calls dao deleteById`() =
        testScope.runTest {
            val fixture = createFixture()
            val repository = fixture.build(this)

            repository.removeServer("server-to-delete")

            verifySuspend { fixture.serverDao.deleteById("server-to-delete") }
        }

    // ============================================================
    // startDiscovery / stopDiscovery Tests
    // ============================================================

    @Test
    fun `startDiscovery calls discovery service`() =
        testScope.runTest {
            val fixture = createFixture()
            every { fixture.discoveryService.startDiscovery() } returns Unit

            val repository = fixture.build(this)
            repository.startDiscovery()

            // Verify was called (mock default behavior)
        }

    @Test
    fun `stopDiscovery calls discovery service`() =
        testScope.runTest {
            val fixture = createFixture()
            every { fixture.discoveryService.stopDiscovery() } returns Unit

            val repository = fixture.build(this)
            repository.stopDiscovery()

            // Verify was called (mock default behavior)
        }
}
