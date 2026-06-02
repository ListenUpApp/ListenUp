package com.calypsan.listenup.server.mdns

/**
 * The data advertised for one ListenUp server instance over mDNS/DNS-SD.
 *
 * @property instanceName the service instance name (the OS hostname) — the per-record owner name
 *   becomes "<instanceName>._listenup._tcp.local" and the host record "<instanceName>.local".
 * @property port the server's HTTP port.
 * @property txt the TXT key/value pairs (id, name, version, api) — encoded as "key=value" strings.
 */
data class MdnsServiceInfo(
    val instanceName: String,
    val port: Int,
    val txt: Map<String, String>,
) {
    companion object {
        const val SERVICE_TYPE = "_listenup._tcp.local"
        const val META_QUERY = "_services._dns-sd._udp.local"
        const val LOCAL = "local"
    }
}
