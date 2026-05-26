package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.AuthServiceAuthed
import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.api.PlaybackProgressService
import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.PingService
import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.SearchService
import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.PlaybackProgressServiceImpl
import com.calypsan.listenup.server.api.PlaybackServiceImpl
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.rpcguard.guard
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.registerService

private class PingServiceImpl : PingService {
    override suspend fun ping(): AppResult<String> = AppResult.Success("pong")
}

private const val AUTH_WALL_REGRESSION_MSG =
    "authed RPC mount reached without a principal — auth wall regression"

/**
 * Mounts kRPC at two endpoints:
 *  - `/api/rpc/public` — anonymous; routes [AuthServicePublic] (login/register/
 *    setupRoot/refresh) and [PingService].
 *  - `/api/rpc/authed` — gated behind [JWT_PROVIDER]; routes [AuthServiceAuthed]
 *    (logout/logoutAll/currentUser/listSessions/decide) and [BookService]
 *    (getBook/searchBooks).
 *
 * The split mirrors the contract's trust boundary structurally — the URL
 * reflects which methods need a session, no fallback hacks needed.
 *
 * The KSP-generated `<Service>Guarded` decorator wraps every `registerService`
 * factory below. Domain failures already cross the wire as typed `AppError`
 * via `AppResult<T>` returns; the guard catches anything that *escapes* a
 * service (bugs, infra faults), logs it server-side with the correlation id,
 * and returns a sanitized `InternalError`. Stacktraces never cross the wire.
 */
@Suppress("CognitiveComplexMethod", "LongParameterList")
fun Route.rpcRoutes(
    authService: AuthServiceImpl,
    scannerService: ScannerService? = null,
    bookService: BookService? = null,
    contributorService: ContributorService? = null,
    seriesService: SeriesService? = null,
    playbackService: PlaybackService? = null,
    playbackProgressService: PlaybackProgressService? = null,
    metadataLookupService: MetadataLookupService? = null,
    searchService: SearchService? = null,
    libraryAdminService: LibraryAdminService? = null,
) {
    rpc("/api/rpc/public") {
        rpcConfig { serialization { json(contractJson) } }
        registerService<PingService> { guard(PingServiceImpl()) }
        registerService<AuthServicePublic> { guard(authService as AuthServicePublic) }
        if (scannerService != null) {
            registerService<ScannerService> { guard(scannerService) }
        }
    }

    authenticate(JWT_PROVIDER) {
        rpc("/api/rpc/authed") {
            rpcConfig { serialization { json(contractJson) } }
            registerService<AuthServiceAuthed> {
                val p =
                    call.userPrincipalOrNull()
                        ?: error(AUTH_WALL_REGRESSION_MSG)
                guard(authService.copyWith(PrincipalProvider { p }) as AuthServiceAuthed)
            }
            if (bookService != null) {
                registerService<BookService> { guard(bookService) }
            }
            if (contributorService != null) {
                registerService<ContributorService> { guard(contributorService) }
            }
            if (seriesService != null) {
                registerService<SeriesService> { guard(seriesService) }
            }
            if (playbackService != null) {
                registerService<PlaybackService> {
                    val p =
                        call.userPrincipalOrNull()
                            ?: error(AUTH_WALL_REGRESSION_MSG)
                    guard((playbackService as PlaybackServiceImpl).copyWith(PrincipalProvider { p }))
                }
            }
            if (playbackProgressService != null) {
                registerService<PlaybackProgressService> {
                    val p =
                        call.userPrincipalOrNull()
                            ?: error(AUTH_WALL_REGRESSION_MSG)
                    guard((playbackProgressService as PlaybackProgressServiceImpl).copyWith(PrincipalProvider { p }))
                }
            }
            if (metadataLookupService != null) {
                registerService<MetadataLookupService> { guard(metadataLookupService) }
            }
            if (searchService != null) {
                registerService<SearchService> { guard(searchService) }
            }
            if (libraryAdminService != null) {
                // TODO: gate by user permissions when Multi-user lands
                registerService<LibraryAdminService> { guard(libraryAdminService) }
            }
        }
    }
}
