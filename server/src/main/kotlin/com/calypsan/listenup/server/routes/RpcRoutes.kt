package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.AdminUserService
import com.calypsan.listenup.api.AuthServiceAuthed
import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.api.InviteService
import com.calypsan.listenup.api.InviteServicePublic
import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.api.PlaybackProgressService
import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.PingService
import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.SearchService
import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.TagService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.AdminUserServiceImpl
import com.calypsan.listenup.server.api.BookServiceImpl
import com.calypsan.listenup.server.api.CollectionServiceImpl
import com.calypsan.listenup.server.api.ContributorServiceImpl
import com.calypsan.listenup.server.api.GenreServiceImpl
import com.calypsan.listenup.server.api.InviteServiceImpl
import com.calypsan.listenup.server.api.LibraryAdminServiceImpl
import com.calypsan.listenup.server.api.MetadataLookupServiceImpl
import com.calypsan.listenup.server.api.PlaybackProgressServiceImpl
import com.calypsan.listenup.server.api.PlaybackServiceImpl
import com.calypsan.listenup.server.api.SearchServiceImpl
import com.calypsan.listenup.server.api.SeriesServiceImpl
import com.calypsan.listenup.server.api.TagServiceImpl
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
@Suppress("LongParameterList")
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
    tagService: TagService? = null,
    genreService: GenreService? = null,
    collectionService: CollectionService? = null,
    adminUserService: AdminUserService? = null,
    inviteService: InviteServiceImpl? = null,
) {
    publicRpc(authService, scannerService, inviteService)
    authenticate(JWT_PROVIDER) {
        authedRpc(
            authService,
            bookService,
            contributorService,
            seriesService,
            playbackService,
            playbackProgressService,
            metadataLookupService,
            searchService,
            libraryAdminService,
            tagService,
            genreService,
            collectionService,
            adminUserService,
            inviteService,
        )
    }
}

/** Mounts the anonymous `/api/rpc/public` services: ping, public auth, and (when wired) the public invite surface + scanner. */
private fun Route.publicRpc(
    authService: AuthServiceImpl,
    scannerService: ScannerService?,
    inviteService: InviteServiceImpl?,
) {
    rpc("/api/rpc/public") {
        rpcConfig { serialization { json(contractJson) } }
        registerService<PingService> { guard(PingServiceImpl()) }
        registerService<AuthServicePublic> { guard(authService as AuthServicePublic) }
        if (inviteService != null) {
            registerService<InviteServicePublic> { guard(inviteService as InviteServicePublic) }
        }
        if (scannerService != null) {
            registerService<ScannerService> { guard(scannerService) }
        }
    }
}

/**
 * Mounts the JWT-gated `/api/rpc/authed` services. Each factory scopes its service to the
 * call's principal via `copyWith`; an absent principal here is an auth-wall regression.
 * Must be called inside `authenticate(JWT_PROVIDER)`.
 */
@Suppress("CognitiveComplexMethod", "CyclomaticComplexMethod", "LongParameterList", "LongMethod")
private fun Route.authedRpc(
    authService: AuthServiceImpl,
    bookService: BookService?,
    contributorService: ContributorService?,
    seriesService: SeriesService?,
    playbackService: PlaybackService?,
    playbackProgressService: PlaybackProgressService?,
    metadataLookupService: MetadataLookupService?,
    searchService: SearchService?,
    libraryAdminService: LibraryAdminService?,
    tagService: TagService?,
    genreService: GenreService?,
    collectionService: CollectionService?,
    adminUserService: AdminUserService?,
    inviteService: InviteServiceImpl?,
) {
    rpc("/api/rpc/authed") {
        rpcConfig { serialization { json(contractJson) } }
        registerService<AuthServiceAuthed> {
            val p =
                call.userPrincipalOrNull()
                    ?: error(AUTH_WALL_REGRESSION_MSG)
            guard(authService.copyWith(PrincipalProvider { p }) as AuthServiceAuthed)
        }
        if (bookService != null) {
            registerService<BookService> {
                val p =
                    call.userPrincipalOrNull()
                        ?: error(AUTH_WALL_REGRESSION_MSG)
                guard((bookService as BookServiceImpl).copyWith(PrincipalProvider { p }))
            }
        }
        if (contributorService != null) {
            registerService<ContributorService> {
                val p =
                    call.userPrincipalOrNull()
                        ?: error(AUTH_WALL_REGRESSION_MSG)
                guard((contributorService as ContributorServiceImpl).copyWith(PrincipalProvider { p }))
            }
        }
        if (seriesService != null) {
            registerService<SeriesService> {
                val p =
                    call.userPrincipalOrNull()
                        ?: error(AUTH_WALL_REGRESSION_MSG)
                guard((seriesService as SeriesServiceImpl).copyWith(PrincipalProvider { p }))
            }
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
            registerService<MetadataLookupService> {
                val p =
                    call.userPrincipalOrNull()
                        ?: error(AUTH_WALL_REGRESSION_MSG)
                guard((metadataLookupService as MetadataLookupServiceImpl).copyWith(PrincipalProvider { p }))
            }
        }
        if (searchService != null) {
            registerService<SearchService> {
                val p =
                    call.userPrincipalOrNull()
                        ?: error(AUTH_WALL_REGRESSION_MSG)
                guard((searchService as SearchServiceImpl).copyWith(PrincipalProvider { p }))
            }
        }
        if (libraryAdminService != null) {
            registerService<LibraryAdminService> {
                val p =
                    call.userPrincipalOrNull()
                        ?: error(AUTH_WALL_REGRESSION_MSG)
                guard((libraryAdminService as LibraryAdminServiceImpl).copyWith(PrincipalProvider { p }))
            }
        }
        if (tagService != null) {
            registerService<TagService> {
                val p =
                    call.userPrincipalOrNull()
                        ?: error(AUTH_WALL_REGRESSION_MSG)
                guard((tagService as TagServiceImpl).copyWith(PrincipalProvider { p }))
            }
        }
        if (genreService != null) {
            registerService<GenreService> {
                val p =
                    call.userPrincipalOrNull()
                        ?: error(AUTH_WALL_REGRESSION_MSG)
                guard((genreService as GenreServiceImpl).copyWith(PrincipalProvider { p }))
            }
        }
        if (collectionService != null) {
            registerService<CollectionService> {
                val p =
                    call.userPrincipalOrNull()
                        ?: error(AUTH_WALL_REGRESSION_MSG)
                guard((collectionService as CollectionServiceImpl).copyWith(PrincipalProvider { p }))
            }
        }
        if (adminUserService != null) {
            registerService<AdminUserService> {
                val p =
                    call.userPrincipalOrNull()
                        ?: error(AUTH_WALL_REGRESSION_MSG)
                guard((adminUserService as AdminUserServiceImpl).copyWith(PrincipalProvider { p }))
            }
        }
        if (inviteService != null) {
            registerService<InviteService> {
                val p =
                    call.userPrincipalOrNull()
                        ?: error(AUTH_WALL_REGRESSION_MSG)
                guard(inviteService.copyWith(PrincipalProvider { p }) as InviteService)
            }
        }
    }
}
