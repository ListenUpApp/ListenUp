package com.calypsan.listenup.client.data.discovery

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

private val logger = KotlinLogging.logger {}

/**
 * JVM/Desktop implementation of [ServerDiscoveryService] using JmDNS.
 *
 * Discovers ListenUp servers advertising via mDNS/Zeroconf on the local network.
 * Service type: _listenup._tcp.local.
 *
 * TXT record parsing:
 * - id: Server's unique identifier (required)
 * - name: Human-readable name (required)
 * - version: Server version (required)
 * - api: API version (required)
 * - remote: Remote URL (optional)
 */
class JmDnsDiscoveryService : ServerDiscoveryService {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serversState = MutableStateFlow<Map<String, DiscoveredServer>>(emptyMap())

    private var jmdns: JmDNS? = null
    private var serviceListener: ServiceListener? = null
    private var isDiscovering = false

    companion object {
        private const val SERVICE_TYPE = "_listenup._tcp.local."
    }

    override fun discover(): Flow<List<DiscoveredServer>> = serversState.map { it.values.toList() }

    override fun startDiscovery() {
        if (isDiscovering) {
            logger.debug { "Discovery already running" }
            return
        }

        scope.launch {
            try {
                logger.info { "Starting JmDNS discovery for service type: '$SERVICE_TYPE'" }

                // Create JmDNS instance
                jmdns = JmDNS.create().also { dns ->
                    logger.info { "JmDNS created on ${dns.inetAddress?.hostAddress ?: "unknown"}" }

                    // Create service listener
                    serviceListener = object : ServiceListener {
                        override fun serviceAdded(event: ServiceEvent) {
                            logger.info { "Service found: ${event.name}, requesting info..." }
                            // Request service info to get TXT records
                            dns.requestServiceInfo(event.type, event.name, true)
                        }

                        override fun serviceRemoved(event: ServiceEvent) {
                            logger.info { "Service removed: ${event.name}" }
                            // Remove by name since we might not have the ID
                            serversState.update { current ->
                                val removedId = current.entries
                                    .firstOrNull { it.value.name == event.name }
                                    ?.key
                                if (removedId != null) current - removedId else current
                            }
                        }

                        override fun serviceResolved(event: ServiceEvent) {
                            logger.info { "Service resolved: ${event.name} at ${event.info?.hostAddresses?.firstOrNull()}:${event.info?.port}" }
                            val server = parseDiscoveredServer(event)
                            if (server != null) {
                                serversState.update { it + (server.id to server) }
                                logger.info { "Server discovered: ${server.name} (${server.id}) at ${server.localUrl}" }
                            } else {
                                logger.warn { "Failed to parse server info: ${event.name}" }
                            }
                        }
                    }

                    // Add listener
                    dns.addServiceListener(SERVICE_TYPE, serviceListener)
                    isDiscovering = true
                    logger.info { "JmDNS discovery started successfully" }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to start JmDNS discovery" }
                isDiscovering = false
            }
        }
    }

    override fun stopDiscovery() {
        if (!isDiscovering) {
            logger.debug { "Discovery not running, nothing to stop" }
            return
        }

        logger.info { "Stopping JmDNS discovery" }

        try {
            serviceListener?.let { listener ->
                jmdns?.removeServiceListener(SERVICE_TYPE, listener)
            }
            jmdns?.close()
        } catch (e: Exception) {
            logger.error(e) { "Error stopping JmDNS discovery" }
        } finally {
            jmdns = null
            serviceListener = null
            isDiscovering = false
            serversState.value = emptyMap()
        }
    }

    private fun parseDiscoveredServer(event: ServiceEvent): DiscoveredServer? {
        val info = event.info ?: return null

        // Get host address
        val host = info.hostAddresses?.firstOrNull() ?: info.inet4Addresses?.firstOrNull()?.hostAddress
        if (host == null) {
            logger.warn { "No host address found for ${event.name}" }
            return null
        }

        val port = info.port

        // Parse TXT records
        val id = info.getPropertyString("id")
        if (id == null) {
            logger.warn { "No 'id' in TXT records for ${event.name}" }
            return null
        }

        val name = info.getPropertyString("name") ?: event.name
        val apiVersion = info.getPropertyString("api") ?: "v1"
        val serverVersion = info.getPropertyString("version") ?: "unknown"
        val remoteUrl = info.getPropertyString("remote")

        return DiscoveredServer(
            id = id,
            name = name,
            host = host,
            port = port,
            apiVersion = apiVersion,
            serverVersion = serverVersion,
            remoteUrl = remoteUrl,
        )
    }
}
