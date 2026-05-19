package com.calypsan.listenup.client.data.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

private val logger = KotlinLogging.logger {}

/**
 * Android implementation of [ServerDiscoveryService] using NsdManager.
 *
 * Discovers ListenUp servers advertising via mDNS on the local network.
 * Service type: _listenup._tcp
 *
 * TXT record parsing:
 * - id: Server's unique identifier (required)
 * - name: Human-readable name (required)
 * - version: Server version (required)
 * - api: API version (required)
 * - remote: Remote URL (optional)
 */
class NsdDiscoveryService(
    context: Context,
) : ServerDiscoveryService {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serversState = MutableStateFlow<Map<String, DiscoveredServer>>(emptyMap())

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var isDiscovering = false

    companion object {
        private const val SERVICE_TYPE = "_listenup._tcp."
    }

    override fun discover(): Flow<List<DiscoveredServer>> = serversState.map { it.values.toList() }

    override fun startDiscovery() {
        if (isDiscovering) {
            logger.debug { "Discovery already running" }
            return
        }

        logger.info { "Starting mDNS discovery for service type: '$SERVICE_TYPE'" }

        discoveryListener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {
                    logger.info { "mDNS discovery started successfully for: '$serviceType'" }
                    isDiscovering = true
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    logger.info { "mDNS discovery stopped for: '$serviceType'" }
                    isDiscovering = false
                }

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    logger.info {
                        "mDNS service found: name='${serviceInfo.serviceName}', type='${serviceInfo.serviceType}'"
                    }
                    resolveService(serviceInfo)
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    logger.info { "mDNS service lost: name='${serviceInfo.serviceName}'" }
                    // Remove by service name since we don't have the ID yet
                    serversState.update { current ->
                        val removedId =
                            current.entries
                                .firstOrNull { it.value.name == serviceInfo.serviceName }
                                ?.key
                        if (removedId != null) current - removedId else current
                    }
                }

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    val errorName = nsdErrorCodeToString(errorCode)
                    logger.error {
                        "mDNS discovery start FAILED: errorCode=$errorCode ($errorName), serviceType='$serviceType'"
                    }
                    isDiscovering = false
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    val errorName = nsdErrorCodeToString(errorCode)
                    logger.error { "mDNS discovery stop FAILED: errorCode=$errorCode ($errorName)" }
                }
            }

        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            logger.error(e) { "Failed to start discovery" }
            isDiscovering = false
        }
    }

    override fun stopDiscovery() {
        if (!isDiscovering || discoveryListener == null) {
            logger.debug { "Discovery not running, nothing to stop" }
            return
        }

        logger.info { "Stopping mDNS discovery" }
        try {
            nsdManager.stopServiceDiscovery(discoveryListener)
        } catch (e: Exception) {
            logger.error(e) { "Failed to stop discovery" }
        }
        discoveryListener = null
        isDiscovering = false
    }

    private fun resolveService(serviceInfo: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: Use the new ServiceInfoCallback API
            resolveServiceModern(serviceInfo)
        } else {
            // API 33 and below: Use the deprecated ResolveListener API
            @Suppress("DEPRECATION")
            resolveServiceLegacy(serviceInfo)
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveServiceLegacy(serviceInfo: NsdServiceInfo) {
        val resolveListener =
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    logger.error { "Resolve failed for ${serviceInfo.serviceName}: $errorCode" }
                }

                @Suppress("DEPRECATION")
                override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                    val hostAddress = resolvedInfo.host?.hostAddress
                    logger.debug { "Resolved: ${resolvedInfo.serviceName} at $hostAddress:${resolvedInfo.port}" }
                    val server = parseDiscoveredServer(resolvedInfo)
                    if (server != null) {
                        serversState.update { it + (server.id to server) }
                        logger.info { "Server discovered: ${server.name} (${server.id}) at ${server.localUrl}" }
                    } else {
                        logger.warn { "Failed to parse server: ${resolvedInfo.serviceName}" }
                    }
                }
            }

        try {
            nsdManager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            logger.error(e) { "Failed to resolve service" }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun resolveServiceModern(serviceInfo: NsdServiceInfo) {
        val executor = Executors.newSingleThreadExecutor()
        var callback: NsdManager.ServiceInfoCallback? = null

        callback =
            object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    logger.error { "ServiceInfoCallback registration failed: $errorCode" }
                }

                override fun onServiceUpdated(resolvedInfo: NsdServiceInfo) {
                    val hostAddress = resolvedInfo.hostAddresses.firstOrNull()?.hostAddress
                    logger.debug { "Resolved: ${resolvedInfo.serviceName} at $hostAddress:${resolvedInfo.port}" }
                    val server = parseDiscoveredServer(resolvedInfo)
                    if (server != null) {
                        serversState.update { it + (server.id to server) }
                        logger.info { "Server discovered: ${server.name} (${server.id}) at ${server.localUrl}" }
                    } else {
                        logger.warn { "Failed to parse server: ${resolvedInfo.serviceName}" }
                    }
                    // Unregister after successful resolution
                    callback?.let { nsdManager.unregisterServiceInfoCallback(it) }
                }

                override fun onServiceLost() {
                    logger.debug { "Service lost during resolution: ${serviceInfo.serviceName}" }
                    callback?.let { nsdManager.unregisterServiceInfoCallback(it) }
                }

                override fun onServiceInfoCallbackUnregistered() {
                    logger.debug { "ServiceInfoCallback unregistered for ${serviceInfo.serviceName}" }
                }
            }

        try {
            nsdManager.registerServiceInfoCallback(serviceInfo, executor, callback)
        } catch (e: Exception) {
            logger.error(e) { "Failed to register service info callback" }
        }
    }

    private fun parseDiscoveredServer(serviceInfo: NsdServiceInfo): DiscoveredServer? {
        val host = getHostAddress(serviceInfo) ?: return null
        val port = serviceInfo.port

        // Parse TXT records (available on Android 7.0+)
        val txtRecords = parseTxtRecords(serviceInfo)

        val id = txtRecords["id"] ?: return null
        val name = txtRecords["name"] ?: serviceInfo.serviceName
        val apiVersion = txtRecords["api"] ?: "v1"
        val serverVersion = txtRecords["version"] ?: "unknown"
        val remoteUrl = txtRecords["remote"]

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

    @Suppress("DEPRECATION")
    private fun getHostAddress(serviceInfo: NsdServiceInfo): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            serviceInfo.hostAddresses.firstOrNull()?.hostAddress
        } else {
            serviceInfo.host?.hostAddress
        }

    private fun parseTxtRecords(serviceInfo: NsdServiceInfo): Map<String, String> {
        val result = mutableMapOf<String, String>()

        // NsdServiceInfo.attributes is available on API 21+
        try {
            val attributes = serviceInfo.attributes
            for ((key, value) in attributes) {
                if (value != null) {
                    result[key] = String(value, Charsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse TXT records" }
        }

        return result
    }

    /**
     * Convert NSD error codes to human-readable strings for debugging.
     */
    private fun nsdErrorCodeToString(errorCode: Int): String =
        when (errorCode) {
            NsdManager.FAILURE_INTERNAL_ERROR -> "FAILURE_INTERNAL_ERROR"
            NsdManager.FAILURE_ALREADY_ACTIVE -> "FAILURE_ALREADY_ACTIVE"
            NsdManager.FAILURE_MAX_LIMIT -> "FAILURE_MAX_LIMIT"
            else -> "UNKNOWN_ERROR"
        }
}
