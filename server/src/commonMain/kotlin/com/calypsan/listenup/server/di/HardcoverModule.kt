package com.calypsan.listenup.server.di

import com.calypsan.listenup.api.HardcoverService
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.server.api.HardcoverServiceImpl
import com.calypsan.listenup.server.auth.JwtConfiguration
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.hardcover.HardcoverBookIdentities
import com.calypsan.listenup.server.hardcover.HardcoverBookLinkStore
import com.calypsan.listenup.server.hardcover.HardcoverBookLinking
import com.calypsan.listenup.server.hardcover.HardcoverBookMatcher
import com.calypsan.listenup.server.hardcover.HardcoverConnectionStore
import com.calypsan.listenup.server.hardcover.HardcoverGraphQlClient
import com.calypsan.listenup.server.hardcover.HardcoverLinker
import com.calypsan.listenup.server.hardcover.HardcoverMatchBackfill
import com.calypsan.listenup.server.hardcover.HardcoverOAuthClient
import com.calypsan.listenup.server.hardcover.HardcoverOutbox
import com.calypsan.listenup.server.hardcover.HardcoverPullRequests
import com.calypsan.listenup.server.hardcover.HardcoverPullStore
import com.calypsan.listenup.server.hardcover.HardcoverPullWorker
import com.calypsan.listenup.server.hardcover.HardcoverPuller
import com.calypsan.listenup.server.hardcover.HardcoverPushExecutor
import com.calypsan.listenup.server.hardcover.HardcoverPushHook
import com.calypsan.listenup.server.hardcover.HardcoverPushNudge
import com.calypsan.listenup.server.hardcover.HardcoverPushRecorder
import com.calypsan.listenup.server.hardcover.HardcoverPushWorker
import com.calypsan.listenup.server.hardcover.HardcoverRateLimiter
import com.calypsan.listenup.server.hardcover.HardcoverRatingConnection
import com.calypsan.listenup.server.hardcover.HardcoverRatingSource
import com.calypsan.listenup.server.hardcover.HardcoverShelfResolver
import com.calypsan.listenup.server.hardcover.HardcoverTokenCipher
import com.calypsan.listenup.server.hardcover.HardcoverTokenProvider
import com.calypsan.listenup.server.hardcover.HardcoverUserBooks
import com.calypsan.listenup.server.hardcover.HardcoverUserGate
import com.calypsan.listenup.server.ratings.toIdentity
import com.calypsan.listenup.server.services.BookRepository
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
 * which the application cancels at shutdown), the [HardcoverTokenProvider], the [HardcoverRatingSource]
 * the metadata registry lists, and [HardcoverService]. It also binds Hardcover push and matching: the
 * outbox, the per-user push worker, the recorder `StatsRecorder` calls, the background match pass,
 * manual linking, and the pull (its store, the shelf resolver, the puller and the per-user pull worker).
 *
 * [clientId] is null when the operator hasn't set `hardcover.clientId` (resolved once at startup by
 * `Application.resolveHardcoverClientId`). The graph is built either way, so watching and
 * disconnecting keep working; only [HardcoverService.startLink] refuses, with NotConfigured.
 *
 * [apiBaseUrl] is where both clients send requests (`hardcover.apiBaseUrl`, resolved by
 * `Application.resolveHardcoverApiBaseUrl`): Hardcover itself, unless a test points it at a fake.
 *
 * The cipher's key derives from the JWT secret, so a backup restored onto a server with a different
 * secret reads its connections as broken instead of handing out someone else's tokens.
 */
fun hardcoverModule(
    clientId: String?,
    apiBaseUrl: String,
    applicationScope: CoroutineScope,
): Module =
    module {
        single { HardcoverTokenCipher(HardcoverTokenCipher.deriveKey(get<JwtConfiguration>().secret)) }
        single(HARDCOVER_HTTP) { hardcoverHttpClient() }
        single {
            HardcoverOAuthClient(
                http = get(HARDCOVER_HTTP),
                clientId = clientId.orEmpty(),
                apiBaseUrl = apiBaseUrl,
            )
        }
        single { HardcoverGraphQlClient(http = get(HARDCOVER_HTTP), apiBaseUrl = apiBaseUrl) }
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
        single { HardcoverRateLimiter() }
        single { HardcoverUserGate() }
        single { HardcoverRatingConnection(store = get(), tokens = get()) }
        single {
            HardcoverRatingSource(
                graphQl = get(),
                connection = get(),
                rateLimiter = get(),
                clientConfigured = clientId != null,
            )
        }
        single { HardcoverUserBooks(graphQl = get()) }
        single { HardcoverBookMatcher(graphQl = get(), rateLimiter = get()) }
        single { HardcoverBookLinkStore(sql = get(), clock = get()) }
        single { HardcoverOutbox(sql = get(), clock = get()) }
        single<HardcoverBookIdentities> {
            val books = get<BookRepository>()
            HardcoverBookIdentities { bookId -> books.findById(BookId(bookId))?.toIdentity() }
        }
        single { HardcoverPushExecutor(userBooks = get(), links = get(), outbox = get(), sql = get(), clock = get()) }
        single {
            HardcoverPushWorker(
                outbox = get(),
                links = get(),
                matcher = get(),
                executor = get(),
                tokens = get(),
                connections = get(),
                linker = get(),
                identities = get(),
                gate = get(),
                clock = get(),
            )
        }
        single<HardcoverPushNudge> { get<HardcoverPushWorker>() }
        single {
            HardcoverPushRecorder(
                sql = get(),
                connections = get(),
                outbox = get(),
                links = get(),
                nudge = get(),
                clock = get(),
            )
        }
        single<HardcoverPushHook> { get<HardcoverPushRecorder>() }
        single {
            HardcoverMatchBackfill(
                links = get(),
                matcher = get(),
                tokens = get(),
                identities = get(),
                scope = applicationScope,
            )
        }
        single { HardcoverPullStore(sql = get(), clock = get()) }
        single { HardcoverShelfResolver(sql = get(), access = get()) }
        single {
            HardcoverPuller(
                userBooks = get(),
                store = get(),
                resolver = get(),
                links = get(),
                rateLimiter = get(),
                sql = get(),
                clock = get(),
            )
        }
        single {
            HardcoverPullWorker(
                puller = get(),
                store = get(),
                tokens = get(),
                connections = get(),
                linker = get(),
                gate = get(),
                pushNudge = get(),
                clock = get(),
            )
        }
        single<HardcoverPullRequests> { get<HardcoverPullWorker>() }
        single {
            HardcoverBookLinking(
                graphQl = get(),
                tokens = get(),
                connections = get(),
                links = get(),
                outbox = get(),
                nudge = get(),
                access = get(),
                rateLimiter = get(),
                pulls = get(),
            )
        }
        single {
            HardcoverServiceImpl(
                linker = get(),
                clientIdConfigured = clientId != null,
                linking = get(),
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
