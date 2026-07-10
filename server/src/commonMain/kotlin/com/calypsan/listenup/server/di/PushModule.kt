package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.PushService
import com.calypsan.listenup.server.api.PushServiceImpl
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.push.NoOpPushNotifier
import com.calypsan.listenup.server.push.PushConfig
import com.calypsan.listenup.server.push.PushNotifier
import com.calypsan.listenup.server.push.PushRelayClient
import com.calypsan.listenup.server.push.RelayPushNotifier
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import org.koin.core.module.Module
import org.koin.dsl.module

private const val PUSH_REQUEST_TIMEOUT_MS = 10_000L
private const val PUSH_CONNECT_TIMEOUT_MS = 5_000L

/**
 * Koin module for the push-notification slice: [PushNotifier] selection and the session-bound
 * device-token registry ([PushService]). Depends on [PushConfig] (bound in `authModule`,
 * resolved once at startup from `Application.resolvePushRelayUrl`).
 *
 * [PushRelayClient] is constructed only inside the `config.configured` branch below — never as
 * its own Koin `single` — so a relay-less fork's DI graph never evaluates `relayUrl!!`. That
 * keeps [NoOpPushNotifier] a true fallback: no relay URL configured, no relay client built, no
 * startup crash.
 */
fun pushModule(): Module =
    module {
        single<PushNotifier> {
            val config = get<PushConfig>()
            if (config.configured) {
                RelayPushNotifier(
                    db = get<ListenUpDatabase>(),
                    relay = PushRelayClient(relayUrl = config.relayUrl!!.removeSuffix("/"), http = pushHttpClient()),
                    settings = get(),
                    clock = get(),
                )
            } else {
                NoOpPushNotifier()
            }
        }

        single {
            PushServiceImpl(
                db = get<ListenUpDatabase>(),
                pushConfig = get(),
                settings = get(),
                notifier = get(),
                clock = get(),
                principal =
                    PrincipalProvider {
                        error("Unscoped PushService — call copyWith(PrincipalProvider) at the route")
                    },
            )
        }
        single<PushService> { get<PushServiceImpl>() }
    }

/**
 * Dedicated [HttpClient] for outbound calls to the push relay — separate from the metadata
 * client's lenient/unknown-keys JSON config since the relay protocol is our own, tightly
 * specified wire format. JSON content negotiation for [PushRelayClient.RelayResponse]
 * decoding, plus a short request/connect timeout budget (push is best-effort — see
 * [PushNotifier] KDoc — so a hung relay must not block the caller for long).
 */
private fun pushHttpClient(): HttpClient =
    metadataHttpClient {
        install(ContentNegotiation) { json() }
        install(HttpTimeout) {
            requestTimeoutMillis = PUSH_REQUEST_TIMEOUT_MS
            connectTimeoutMillis = PUSH_CONNECT_TIMEOUT_MS
        }
    }
