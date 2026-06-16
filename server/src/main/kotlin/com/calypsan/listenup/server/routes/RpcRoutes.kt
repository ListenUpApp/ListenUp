package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.ActivityService
import com.calypsan.listenup.api.AdminSettingsService
import com.calypsan.listenup.api.AdminUserService
import com.calypsan.listenup.api.AuthServiceAuthed
import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.BackupService
import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.api.ImportService
import com.calypsan.listenup.api.InstanceService
import com.calypsan.listenup.api.InviteService
import com.calypsan.listenup.api.InviteServicePublic
import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.api.MoodService
import com.calypsan.listenup.api.PlaybackProgressService
import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.PingService
import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.SearchService
import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.ShelfService
import com.calypsan.listenup.api.SocialService
import com.calypsan.listenup.api.TagService
import com.calypsan.listenup.api.UserPreferencesService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.api.result.AppResult
import com.calypsan.listenup.server.api.ActivityServiceImpl
import com.calypsan.listenup.server.api.AdminSettingsServiceImpl
import com.calypsan.listenup.server.api.AdminUserServiceImpl
import com.calypsan.listenup.server.api.BackupServiceImpl
import com.calypsan.listenup.server.api.BookServiceImpl
import com.calypsan.listenup.server.api.CollectionServiceImpl
import com.calypsan.listenup.server.api.ProfileServiceImpl
import com.calypsan.listenup.server.api.ContributorServiceImpl
import com.calypsan.listenup.server.api.GenreServiceImpl
import com.calypsan.listenup.server.api.ImportServiceImpl
import com.calypsan.listenup.server.api.InviteServiceImpl
import com.calypsan.listenup.server.api.LibraryAdminServiceImpl
import com.calypsan.listenup.server.api.MetadataLookupServiceImpl
import com.calypsan.listenup.server.api.MoodServiceImpl
import com.calypsan.listenup.server.api.PlaybackProgressServiceImpl
import com.calypsan.listenup.server.api.PlaybackServiceImpl
import com.calypsan.listenup.server.api.SearchServiceImpl
import com.calypsan.listenup.server.api.SeriesServiceImpl
import com.calypsan.listenup.server.api.ShelfServiceImpl
import com.calypsan.listenup.server.api.SocialServiceImpl
import com.calypsan.listenup.server.api.TagServiceImpl
import com.calypsan.listenup.server.api.UserPreferencesServiceImpl
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.rpcguard.guard
import com.calypsan.listenup.server.auth.PrincipalProvider
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.plugins.userPrincipalOrNull
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.Route
import kotlinx.rpc.krpc.ktor.server.KrpcRoute
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.registerService

private class PingServiceImpl : PingService {
    override suspend fun ping(): AppResult<String> = AppResult.Success("pong")
}

private const val AUTH_WALL_REGRESSION_MSG =
    "authed RPC mount reached without a principal — auth wall regression"

/**
 * Registers a per-call principal-scoped service on this kRPC mount.
 *
 * Resolves the call's principal once per service resolution — an absent principal here
 * is an auth-wall regression (the mount is inside `authenticate(JWT_PROVIDER)`) — then
 * hands a [PrincipalProvider] to [scoped], which binds it onto the service via `copyWith`
 * and wraps the result with the generated `guard`. Collapses every authed registration to
 * a single line and keeps the `guard` call at the call site, where the concrete service
 * type selects the right `guard` overload.
 */
private inline fun <reified T : Any> KrpcRoute.registerScoped(crossinline scoped: (PrincipalProvider) -> T) {
    registerService<T> {
        val principal = call.userPrincipalOrNull() ?: error(AUTH_WALL_REGRESSION_MSG)
        scoped(PrincipalProvider { principal })
    }
}

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
    instanceService: InstanceService,
    scannerService: ScannerService,
    bookService: BookService,
    contributorService: ContributorService,
    seriesService: SeriesService,
    playbackService: PlaybackService,
    playbackProgressService: PlaybackProgressService,
    metadataLookupService: MetadataLookupService,
    searchService: SearchService,
    libraryAdminService: LibraryAdminService,
    tagService: TagService,
    moodService: MoodService,
    genreService: GenreService,
    collectionService: CollectionService,
    shelfService: ShelfService,
    socialService: SocialService,
    activityService: ActivityService,
    adminUserService: AdminUserService,
    adminSettingsService: AdminSettingsService,
    inviteService: InviteServiceImpl,
    profileService: ProfileService,
    userPreferencesService: UserPreferencesService,
    backupService: BackupService,
    importService: ImportService,
) {
    publicRpc(authService, instanceService, scannerService, inviteService)
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
            moodService,
            genreService,
            collectionService,
            shelfService,
            socialService,
            activityService,
            adminUserService,
            adminSettingsService,
            inviteService,
            profileService,
            userPreferencesService,
            backupService,
            importService,
        )
    }
}

/**
 * Mounts the anonymous `/api/rpc/public` services: ping, instance verification, public auth,
 * the public invite surface, and scanner.
 */
private fun Route.publicRpc(
    authService: AuthServiceImpl,
    instanceService: InstanceService,
    scannerService: ScannerService,
    inviteService: InviteServiceImpl,
) {
    rpc("/api/rpc/public") {
        rpcConfig { serialization { json(contractJson) } }
        registerService<PingService> { guard(PingServiceImpl()) }
        registerService<InstanceService> { guard(instanceService) }
        registerService<AuthServicePublic> { guard(authService as AuthServicePublic) }
        registerService<InviteServicePublic> { guard(inviteService as InviteServicePublic) }
        registerService<ScannerService> { guard(scannerService) }
    }
}

/**
 * Mounts the JWT-gated `/api/rpc/authed` services. Each factory scopes its service to the
 * call's principal via `copyWith`; an absent principal here is an auth-wall regression.
 * Must be called inside `authenticate(JWT_PROVIDER)`.
 */
@Suppress("LongParameterList")
private fun Route.authedRpc(
    authService: AuthServiceImpl,
    bookService: BookService,
    contributorService: ContributorService,
    seriesService: SeriesService,
    playbackService: PlaybackService,
    playbackProgressService: PlaybackProgressService,
    metadataLookupService: MetadataLookupService,
    searchService: SearchService,
    libraryAdminService: LibraryAdminService,
    tagService: TagService,
    moodService: MoodService,
    genreService: GenreService,
    collectionService: CollectionService,
    shelfService: ShelfService,
    socialService: SocialService,
    activityService: ActivityService,
    adminUserService: AdminUserService,
    adminSettingsService: AdminSettingsService,
    inviteService: InviteServiceImpl,
    profileService: ProfileService,
    userPreferencesService: UserPreferencesService,
    backupService: BackupService,
    importService: ImportService,
) {
    rpc("/api/rpc/authed") {
        rpcConfig { serialization { json(contractJson) } }
        registerScoped<AuthServiceAuthed> { guard(authService.copyWith(it) as AuthServiceAuthed) }
        registerScoped<BookService> { guard((bookService as BookServiceImpl).copyWith(it)) }
        registerScoped<ContributorService> { guard((contributorService as ContributorServiceImpl).copyWith(it)) }
        registerScoped<SeriesService> { guard((seriesService as SeriesServiceImpl).copyWith(it)) }
        registerScoped<PlaybackService> { guard((playbackService as PlaybackServiceImpl).copyWith(it)) }
        registerScoped<PlaybackProgressService> {
            guard((playbackProgressService as PlaybackProgressServiceImpl).copyWith(it))
        }
        registerScoped<MetadataLookupService> {
            guard((metadataLookupService as MetadataLookupServiceImpl).copyWith(it))
        }
        registerScoped<SearchService> { guard((searchService as SearchServiceImpl).copyWith(it)) }
        registerScoped<LibraryAdminService> { guard((libraryAdminService as LibraryAdminServiceImpl).copyWith(it)) }
        registerScoped<TagService> { guard((tagService as TagServiceImpl).copyWith(it)) }
        registerScoped<MoodService> { guard((moodService as MoodServiceImpl).copyWith(it)) }
        registerScoped<GenreService> { guard((genreService as GenreServiceImpl).copyWith(it)) }
        registerScoped<CollectionService> { guard((collectionService as CollectionServiceImpl).copyWith(it)) }
        registerScoped<ShelfService> { guard((shelfService as ShelfServiceImpl).copyWith(it)) }
        registerScoped<SocialService> { guard((socialService as SocialServiceImpl).copyWith(it)) }
        registerScoped<ActivityService> { guard((activityService as ActivityServiceImpl).copyWith(it)) }
        registerScoped<AdminUserService> { guard((adminUserService as AdminUserServiceImpl).copyWith(it)) }
        registerScoped<AdminSettingsService> { guard((adminSettingsService as AdminSettingsServiceImpl).copyWith(it)) }
        registerScoped<InviteService> { guard(inviteService.copyWith(it) as InviteService) }
        registerScoped<ProfileService> { guard((profileService as ProfileServiceImpl).copyWith(it)) }
        registerScoped<UserPreferencesService> {
            guard(
                (userPreferencesService as UserPreferencesServiceImpl).copyWith(it),
            )
        }
        registerScoped<BackupService> { guard((backupService as BackupServiceImpl).copyWith(it)) }
        registerScoped<ImportService> { guard((importService as ImportServiceImpl).copyWith(it)) }
    }
}
