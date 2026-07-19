@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.calypsan.listenup.client.data.discovery

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import platform.Foundation.NSData
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.Foundation.NSNetServiceDelegateProtocol
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.darwin.NSObject
import platform.posix.AF_INET
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret

private val logger = KotlinLogging.logger {}

/**
 * iOS implementation of [ServerDiscoveryService] using NSNetServiceBrowser (Bonjour).
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
internal class AppleDiscoveryService : ServerDiscoveryService {
    private val serviceBrowser = NSNetServiceBrowser()
    private val serversState = MutableStateFlow<Map<String, DiscoveredServer>>(emptyMap())

    /**
     * Guards the bookkeeping maps + [isDiscovering] flag against concurrent mutation from the
     * public start/stop callers and the Bonjour browser/service delegate callbacks (which fire on
     * the scheduling run loop). Mirrors `DownloadSessionDelegate`'s [NSRecursiveLock] pattern.
     */
    private val lock = platform.Foundation.NSRecursiveLock()
    private val pendingServices = mutableMapOf<String, NSNetService>()
    private val serviceDelegates = mutableMapOf<String, ServiceDelegate>()

    private var browserDelegate: BrowserDelegate? = null
    private var isDiscovering = false

    private inline fun <T> withLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        private const val SERVICE_TYPE = "_listenup._tcp."
        private const val SERVICE_DOMAIN = "local."
        private const val RESOLVE_TIMEOUT = 10.0
    }

    override fun discover(): Flow<List<DiscoveredServer>> = serversState.map { it.values.toList() }

    override fun startDiscovery() {
        val delegate =
            withLock {
                if (isDiscovering) {
                    logger.debug { "Discovery already running" }
                    return
                }
                isDiscovering = true
                BrowserDelegate().also { browserDelegate = it }
            }

        logger.info { "Starting mDNS discovery for $SERVICE_TYPE" }
        serviceBrowser.delegate = delegate
        serviceBrowser.searchForServicesOfType(SERVICE_TYPE, inDomain = SERVICE_DOMAIN)
    }

    override fun stopDiscovery() {
        withLock {
            if (!isDiscovering) {
                logger.debug { "Discovery not running, nothing to stop" }
                return
            }
            isDiscovering = false
            browserDelegate = null
            pendingServices.clear()
            serviceDelegates.clear()
            // Also drop the resolved-servers cache: a prior session's resolution of the server at its
            // OLD IP must not linger and short-circuit the next relocate with a stale localUrl. Leaving
            // it is why a relaunch (which starts with an empty map) recovered a moved server but a
            // running relocate did not — 006.
            serversState.value = emptyMap()
        }

        logger.info { "Stopping mDNS discovery" }
        serviceBrowser.stop()
    }

    private fun onServiceFound(service: NSNetService) {
        val serviceName = service.name
        logger.debug { "Service found: $serviceName" }

        val delegate = ServiceDelegate()
        withLock {
            pendingServices[serviceName] = service
            serviceDelegates[serviceName] = delegate
        }
        service.delegate = delegate
        service.resolveWithTimeout(RESOLVE_TIMEOUT)
    }

    private fun onServiceRemoved(service: NSNetService) {
        val serviceName = service.name
        logger.debug { "Service removed: $serviceName" }

        withLock {
            pendingServices.remove(serviceName)
            serviceDelegates.remove(serviceName)
        }
        serversState.update { current ->
            val removedId = current.entries.firstOrNull { it.value.name == serviceName }?.key
            if (removedId != null) current - removedId else current
        }
    }

    private fun onServiceResolved(service: NSNetService) {
        val port = service.port.toInt()
        val serviceName = service.name

        // Extract IPv4 addresses directly from resolved addresses, best-first.
        // Using the IP avoids slow mDNS hostname resolution on every HTTP request.
        // NSNetService.addresses contains sockaddr structs with actual IPs; a multi-homed server
        // resolves to several, and the first can be unroutable — keep them all for fallback.
        val ipAddresses = extractIPv4Addresses(service)
        val rawHostName = service.hostName

        val rankedHosts =
            if (ipAddresses.isNotEmpty()) {
                logger.debug { "Resolved $serviceName to IPs: $ipAddresses (hostname: $rawHostName)" }
                rankHostAddresses(ipAddresses)
            } else if (rawHostName != null) {
                // Fallback to hostname if IP extraction fails
                val normalized = normalizeHostname(rawHostName)
                logger.warn { "Could not extract IP for $serviceName, falling back to hostname: $normalized" }
                listOf(normalized)
            } else {
                logger.warn { "Resolved service has no host or addresses: $serviceName" }
                return
            }
        val hostName = rankedHosts.first()

        val txtData = service.TXTRecordData()
        val txtRecords = parseTxtRecords(txtData)

        val serverId = txtRecords["id"]
        if (serverId == null) {
            logger.warn { "Service missing required 'id' in TXT record: $serviceName" }
            return
        }

        val server =
            DiscoveredServer(
                id = serverId,
                name = txtRecords["name"] ?: serviceName,
                host = hostName,
                port = port,
                apiVersion = txtRecords["api"] ?: "v1",
                serverVersion = txtRecords["version"] ?: "unknown",
                remoteUrl = txtRecords["remote"],
                additionalHosts = rankedHosts.drop(1),
            )

        withLock {
            pendingServices.remove(serviceName)
            serviceDelegates.remove(serviceName)
        }
        serversState.update { it + (server.id to server) }
        logger.info { "Server discovered: ${server.name} (${server.id}) at ${server.localUrl}" }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseTxtRecords(data: NSData?): Map<String, String> {
        if (data == null) return emptyMap()

        val result = mutableMapOf<String, String>()
        try {
            val dictionary = NSNetService.dictionaryFromTXTRecordData(data)
            dictionary.forEach { (key, value) ->
                if (key !is String || value !is NSData) return@forEach
                val string = NSString.create(value, NSUTF8StringEncoding) ?: return@forEach
                result[key] = string.toString()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to parse TXT records" }
        }
        return result
    }

    /**
     * Extract every IPv4 address from NSNetService resolved addresses, in announced order.
     *
     * NSNetService.addresses contains NSData objects wrapping sockaddr structs.
     * We parse these to find each AF_INET (IPv4) address and format it as a dotted-quad
     * string. Using the IPs avoids mDNS resolution on every HTTP request, which can add
     * 5-25 seconds of latency on iOS; collecting all of them lets the connect path fall
     * back when the first is unroutable.
     */
    @Suppress("MagicNumber")
    private fun extractIPv4Addresses(service: NSNetService): List<String> {
        val addresses = service.addresses ?: return emptyList()
        val result = mutableListOf<String>()

        for (i in 0 until addresses.count()) {
            val data = addresses[i] as? NSData ?: continue

            // sockaddr_in is 16 bytes: sa_len(1) + sa_family(1) + sin_port(2) + sin_addr(4) + sin_zero(8)
            if (data.length < 16u) continue

            val ptr = data.bytes ?: continue
            val bytePtr = ptr.reinterpret<ByteVar>()

            // Check address family (offset 1 on Darwin — sa_family is second byte after sa_len).
            // Read the 8 used bytes directly off the foreign pointer; the trailing 8 are sin_zero.
            val family = bytePtr[1].toInt() and 0xFF
            if (family != AF_INET) continue

            // IPv4 address is at offset 4-7 (sin_addr, after sa_len + sa_family + sin_port)
            val a = bytePtr[4].toInt() and 0xFF
            val b = bytePtr[5].toInt() and 0xFF
            val c = bytePtr[6].toInt() and 0xFF
            val d = bytePtr[7].toInt() and 0xFF
            result += "$a.$b.$c.$d"
        }

        return result
    }

    /**
     * Normalizes a hostname from NSNetService for proper mDNS resolution.
     *
     * NSNetService.hostName returns the hostname with a trailing dot (FQDN format).
     * For local mDNS services, iOS requires the ".local" suffix to resolve properly.
     *
     * Cases handled:
     * - "omarchy.local." → "omarchy.local" (already has .local, just trim dot)
     * - "omarchy." → "omarchy.local" (simple hostname, append .local)
     * - "192.168.1.100." → "192.168.1.100" (IP address, just trim dot)
     */
    private fun normalizeHostname(rawHostName: String): String {
        val trimmed = rawHostName.trimEnd('.')

        // If it's an IP address, return as-is
        if (trimmed.all { it.isDigit() || it == '.' }) {
            return trimmed
        }

        // If it already ends with .local, return as-is
        if (trimmed.endsWith(".local", ignoreCase = true)) {
            return trimmed
        }

        // Simple hostname without domain - append .local for mDNS resolution
        return "$trimmed.local"
    }

    /**
     * Delegate for NSNetServiceBrowser events.
     */
    private inner class BrowserDelegate :
        NSObject(),
        NSNetServiceBrowserDelegateProtocol {
        override fun netServiceBrowserWillSearch(browser: NSNetServiceBrowser) {
            logger.info { "Service browser will search" }
        }

        override fun netServiceBrowserDidStopSearch(browser: NSNetServiceBrowser) {
            logger.info { "Service browser stopped searching" }
            withLock { isDiscovering = false }
        }

        override fun netServiceBrowser(
            browser: NSNetServiceBrowser,
            didNotSearch: Map<Any?, *>,
        ) {
            logger.error { "Service browser failed to search: $didNotSearch" }
            withLock { isDiscovering = false }
        }

        @ObjCSignatureOverride
        override fun netServiceBrowser(
            browser: NSNetServiceBrowser,
            didFindService: NSNetService,
            moreComing: Boolean,
        ) {
            onServiceFound(didFindService)
        }

        @ObjCSignatureOverride
        override fun netServiceBrowser(
            browser: NSNetServiceBrowser,
            didRemoveService: NSNetService,
            moreComing: Boolean,
        ) {
            onServiceRemoved(didRemoveService)
        }
    }

    /**
     * Delegate for NSNetService resolution events.
     */
    private inner class ServiceDelegate :
        NSObject(),
        NSNetServiceDelegateProtocol {
        override fun netServiceDidResolveAddress(sender: NSNetService) {
            onServiceResolved(sender)
        }

        override fun netService(
            sender: NSNetService,
            didNotResolve: Map<Any?, *>,
        ) {
            logger.error { "Failed to resolve service ${sender.name}: $didNotResolve" }
            withLock {
                pendingServices.remove(sender.name)
                serviceDelegates.remove(sender.name)
            }
        }
    }
}
