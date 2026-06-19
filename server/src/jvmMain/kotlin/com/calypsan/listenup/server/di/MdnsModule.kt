package com.calypsan.listenup.server.di

import com.calypsan.listenup.server.mdns.InstanceIdentity
import com.calypsan.listenup.server.mdns.MdnsAdvertiser
import com.calypsan.listenup.server.mdns.MulticastMdnsResponder
import com.calypsan.listenup.server.mdns.buildMdnsTxt
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module
import java.net.InetAddress

/**
 * mDNS advertisement wiring. [applicationScope] hosts the responder's receive/announce coroutines;
 * [port] is the server's HTTP port (the advertised SRV port). The advertiser is a **singleton** so the
 * settings-change path can re-announce the live instance (an admin rename must propagate without a
 * restart). Its TXT is rebuilt from live sources on every start/refresh: `id` from the persistent
 * [InstanceIdentity], `name` from the operator's configured server name, `remote` only when a remote
 * URL is set; `version`/`api` are identity constants. Loads unconditionally — no library/DB coupling
 * beyond the instance-id + settings reads, which happen lazily inside the TXT provider.
 */
fun mdnsModule(
    applicationScope: CoroutineScope,
    port: Int,
): Module =
    module {
        single { InstanceIdentity(get<ServerSettingsRepository>()) }
        single<MdnsAdvertiser> {
            val identity = get<InstanceIdentity>()
            val settings = get<ServerSettingsRepository>()
            MulticastMdnsResponder(
                instanceName = hostname(),
                port = port,
                txtProvider = {
                    buildMdnsTxt(identity.instanceId(), settings.serverName(), settings.remoteUrl())
                },
                scope = applicationScope,
                // Host record owner = "listenup-<instanceId>.local", NOT the OS hostname. The host's
                // avahi/mDNSResponder already publishes "<hostname>.local" → every interface address
                // (docker/VPN included); resolving our own unique label keeps a client's view of our
                // addresses limited to the LAN interfaces we actually announce.
                hostLabelProvider = { "listenup-${identity.instanceId()}" },
            )
        }
    }

private fun hostname(): String =
    runCatching { singleLabelHostname(InetAddress.getLocalHost().hostName) }.getOrDefault("listenup-server")

/**
 * Strips a dotted FQDN to the first label (e.g. "host.example.com" → "host").
 * A dotted instanceName would be split by [DnsCodec.encodeName] into extra labels,
 * corrupting DNS names like "<instance>._listenup._tcp.local".
 */
internal fun singleLabelHostname(raw: String): String = raw.substringBefore('.').ifBlank { "listenup-server" }
