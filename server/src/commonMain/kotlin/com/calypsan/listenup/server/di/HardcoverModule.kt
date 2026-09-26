package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.HardcoverService
import com.calypsan.listenup.server.api.HardcoverServiceImpl
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.hardcover.HardcoverConnectionStore
import com.calypsan.listenup.server.hardcover.HardcoverGraphQlClient
import com.calypsan.listenup.server.hardcover.HardcoverLinker
import com.calypsan.listenup.server.hardcover.HardcoverOAuthClient
import com.calypsan.listenup.server.hardcover.HardcoverTokenCipher
import com.calypsan.listenup.server.hardcover.HardcoverTokenProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

private const val HARDCOVER_REQUEST_TIMEOUT_MS = 15_000L
private const val HARDCOVER_CONNECT_TIMEOUT_MS = 5_000L

private val HARDCOVER_HTTP = named("hardcoverHttp")

/**
 * Koin module for the Hardcover connection: the token cipher, the two Hardcover clients over one
 * [HttpClient], the encrypted store, the sign-in [HardcoverLinker] (polling in [applicationScope],
 * which the application cancels at shutdown), the [HardcoverTokenProvider], and [HardcoverService].
 *
 * [clientId] is null when the operator hasn't set `hardcover.clientId` (resolved once at startup by
 * `Application.resolveHardcoverClientId`). The graph is built either way, so watching and
 * disconnecting keep working; only [HardcoverService.startLink] refuses, with NotConfigured.
 *
 * The cipher's key derives from the JWT secret, so a backup restored onto a server with a different
 * secret reads its connections as broken instead of handing out someone else's tokens.
 */
fun hardcoverModule(
    clientId: String?,
    applicationScope: CoroutineScope,
): Module =
    module {
        single { HardcoverTokenCipher(HardcoverTokenCipher.deriveKey(get<JwtConfiguration>().secret)) }
        single(HARDCOVER_HTTP) { hardcoverHttpClient() }
        single { HardcoverOAuthClient(http = get(HARDCOVER_HTTP), clientId = clientId.orEmpty()) }
        single { HardcoverGraphQlClient(http = get(HARDCOVER_HTTP)) }
        single { HardcoverConnectionStore(sql = get(), cipher = get(), clock = get()) }
        single {
            HardcoverLinker(
                oauth = get(),
                graphQl = get(),
                store = get(),
                applicationScope = applicationScope,
                clock = get(),
            )
        }
        single { HardcoverTokenProvider(oauth = get(), store = get(), linker = get(), clock = get()) }
        single {
            HardcoverServiceImpl(
                linker = get(),
                clientIdConfigured = clientId != null,
                principal =
                    PrincipalProvider {
                        error("Unscoped HardcoverService — call copyWith(PrincipalProvider) at the route")
                    },
            )
        }
        single<HardcoverService> { get<HardcoverServiceImpl>() }
    }

/**
 * Dedicated [HttpClient] for Hardcover. No content negotiation: both clients decode Hardcover's
 * JSON themselves, so its error bodies (RFC 6749 `error` codes) are read on their own terms. The
 * timeouts bound a hung Hardcover so a sign-in poll or a refresh fails over to "unavailable" rather
 * than waiting forever.
 */
private fun hardcoverHttpClient(): HttpClient =
    metadataHttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = HARDCOVER_REQUEST_TIMEOUT_MS
            connectTimeoutMillis = HARDCOVER_CONNECT_TIMEOUT_MS
        }
    }
