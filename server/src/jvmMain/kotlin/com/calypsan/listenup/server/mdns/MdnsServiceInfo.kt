package com.calypsan.listenup.server.mdns

/**
 * The data advertised for one ListenUp server instance over mDNS/DNS-SD.
 *
 * @property instanceName the service *instance* label — the browse-visible owner name becomes
 *   "<instanceName>._listenup._tcp.local".
 * @property port the server's HTTP port.
 * @property txt the TXT key/value pairs (id, name, version, api) — encoded as "key=value" strings.
 * @property hostLabel the *host* record label — the SRV target and A record owner become
 *   "<hostLabel>.local". Kept distinct from [instanceName] (and unique per instance, e.g.
 *   "listenup-<id>") so it never collides with the OS hostname that a host avahi/mDNSResponder
 *   already publishes for *every* interface (docker/VPN included). Resolving our own label means a
 *   client only ever sees the addresses we advertise, not the host's full multi-homed A-record set.
 *   Defaults to [instanceName] for callers that don't separate the two (e.g. tests).
 */
data class MdnsServiceInfo(
    val instanceName: String,
    val port: Int,
    val txt: Map<String, String>,
    val hostLabel: String = instanceName,
) {
    companion object {
        const val SERVICE_TYPE = "_listenup._tcp.local"
        const val META_QUERY = "_services._dns-sd._udp.local"
        const val LOCAL = "local"
    }
}
