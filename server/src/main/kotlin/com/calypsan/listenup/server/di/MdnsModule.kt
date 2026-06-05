package com.calypsan.listenup.server.di

import com.calypsan.listenup.server.mdns.InstanceIdentity
import com.calypsan.listenup.server.mdns.MdnsAdvertiser
import com.calypsan.listenup.server.mdns.MdnsServiceInfo
import com.calypsan.listenup.server.mdns.MulticastMdnsResponder
import com.calypsan.listenup.server.mdns.buildMdnsTxt
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module
import java.net.InetAddress

/**
 * mDNS advertisement wiring. [applicationScope] hosts the responder's receive/announce coroutines;
 * [port] is the server's HTTP port (the advertised SRV port). The TXT `id` comes from the persistent
 * [InstanceIdentity]; `name` is the operator's configured server name; `remote` is advertised only when
 * an operator has set a remote URL. All three are resolved async at startup and passed into the advertiser
 * factory; `version`/`api` are the server's identity constants. Loads unconditionally — no library/DB
 * coupling beyond the one-time instance-id + settings read.
 */
fun mdnsModule(
    applicationScope: CoroutineScope,
    port: Int,
): Module =
    module {
        single { InstanceIdentity(get<ServerSettingsRepository>()) }
        factory<MdnsAdvertiser> { (instanceId: String, serverName: String, remoteUrl: String?) ->
            MulticastMdnsResponder(
                service =
                    MdnsServiceInfo(
                        instanceName = hostname(),
                        port = port,
                        txt = buildMdnsTxt(instanceId, serverName, remoteUrl),
                    ),
                scope = applicationScope,
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
