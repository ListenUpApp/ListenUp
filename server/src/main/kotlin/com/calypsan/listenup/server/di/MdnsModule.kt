package com.calypsan.listenup.server.di

import com.calypsan.listenup.server.mdns.InstanceIdentity
import com.calypsan.listenup.server.mdns.MdnsAdvertiser
import com.calypsan.listenup.server.mdns.MdnsServiceInfo
import com.calypsan.listenup.server.mdns.MulticastMdnsResponder
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.dsl.module
import java.net.InetAddress

/**
 * mDNS advertisement wiring. [applicationScope] hosts the responder's receive/announce coroutines;
 * [port] is the server's HTTP port (the advertised SRV port). The TXT `id` comes from the persistent
 * [InstanceIdentity] (resolved async at startup and passed into the advertiser factory); name/version/api
 * are the server's identity constants. Loads unconditionally — no library/DB coupling beyond the
 * one-time instance-id read.
 */
fun mdnsModule(
    applicationScope: CoroutineScope,
    port: Int,
): Module =
    module {
        single { InstanceIdentity(get<ServerSettingsRepository>()) }
        factory { (instanceId: String) ->
            MulticastMdnsResponder(
                service =
                    MdnsServiceInfo(
                        instanceName = hostname(),
                        port = port,
                        txt =
                            linkedMapOf(
                                "id" to instanceId,
                                "name" to SERVER_NAME,
                                "version" to SERVER_VERSION,
                                "api" to API_VERSION,
                            ),
                    ),
                scope = applicationScope,
            ) as MdnsAdvertiser
        }
    }

private fun hostname(): String = runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("listenup-server")

// Mirror InstanceServiceImpl's companion constants. Those are private so cannot be referenced
// directly — these local copies must be kept in sync if the server identity ever changes.
private const val SERVER_NAME = "ListenUp"
private const val SERVER_VERSION = "0.0.1"
private const val API_VERSION = "v1"
