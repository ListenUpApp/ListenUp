package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.ActivityService
import com.calypsan.listenup.api.AdminSettingsService
import com.calypsan.listenup.api.AdminUserService
import com.calypsan.listenup.api.AuthServiceAuthed
import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.BackupService
import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.CampfireService
import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.api.ImportService
import com.calypsan.listenup.api.InviteService
import com.calypsan.listenup.api.InviteServicePublic
import com.calypsan.listenup.api.InstanceService
import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.api.MoodService
import com.calypsan.listenup.api.OrganizeService
import com.calypsan.listenup.api.PlaybackProgressService
import com.calypsan.listenup.api.PingService
import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.api.PushService
import com.calypsan.listenup.api.ReadingOrderService
import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.SearchService
import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.ShelfService
import com.calypsan.listenup.api.SocialService
import com.calypsan.listenup.api.TagService
import com.calypsan.listenup.api.UserPreferencesService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.server.api.ActivityServiceImpl
import com.calypsan.listenup.server.api.AdminSettingsServiceImpl
import com.calypsan.listenup.server.api.AdminUserServiceImpl
import com.calypsan.listenup.server.api.BackupServiceImpl
import com.calypsan.listenup.server.api.BookServiceImpl
import com.calypsan.listenup.server.api.CampfireServiceImpl
import com.calypsan.listenup.server.api.CollectionServiceImpl
import com.calypsan.listenup.server.api.ContributorServiceImpl
import com.calypsan.listenup.server.api.GenreServiceImpl
import com.calypsan.listenup.server.api.ImportServiceImpl
import com.calypsan.listenup.server.api.LibraryAdminServiceImpl
import com.calypsan.listenup.server.api.MetadataLookupServiceImpl
import com.calypsan.listenup.server.api.MoodServiceImpl
import com.calypsan.listenup.server.api.OrganizeServiceImpl
import com.calypsan.listenup.server.api.PlaybackProgressServiceImpl
import com.calypsan.listenup.server.api.PlaybackServiceImpl
import com.calypsan.listenup.server.api.ProfileServiceImpl
import com.calypsan.listenup.server.api.PushServiceImpl
import com.calypsan.listenup.server.api.ReadingOrderServiceImpl
import com.calypsan.listenup.server.api.SearchServiceImpl
import com.calypsan.listenup.server.api.SeriesServiceImpl
import com.calypsan.listenup.server.api.ShelfServiceImpl
import com.calypsan.listenup.server.api.SocialServiceImpl
import com.calypsan.listenup.server.api.TagServiceImpl
import com.calypsan.listenup.server.api.UserPreferencesServiceImpl
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.rpcguard.guard
import com.calypsan.listenup.server.scanner.ScannerServiceImpl
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json
import kotlinx.rpc.registerService

actual fun Route.rpcRoutes(services: RpcServices) {
    // MUST stay byte-identical to the sibling actual (jvm ↔ native/linux). See RpcRoutes.kt KDoc and
    // RpcRoutesActualsParityTest. guard(...) is generated per-target, so the body can't move to commonMain.
    publicRpc(services)
    authenticate(JWT_PROVIDER) { authedRpc(services) }
}

private fun Route.publicRpc(services: RpcServices) {
    rpc("/api/rpc/public") {
        rpcConfig { serialization { json(contractJson) } }
        // guardedConstruction wraps the factory bodies so a `as`-cast failure is sanitized rather
        // than shipped raw to the client (the scoped/authed registrations get it via registerScoped).
        registerService<PingService> { guardedConstruction { guard(PingServiceImpl()) } }
        registerService<InstanceService> { guardedConstruction { guard(services.instanceService) } }
        // C3: bind the caller's remote host so the per-IP login/register/refresh throttle inside
        // AuthServiceImpl keys on it — the RPC public mount is otherwise un-rate-limited (many login
        // messages ride one WebSocket, so throttling the upgrade is useless).
        registerService<AuthServicePublic> {
            guardedConstruction {
                guard(services.authService.withRemoteHost(call.request.origin.remoteHost) as AuthServicePublic)
            }
        }
        registerService<InviteServicePublic> {
            guardedConstruction { guard(services.inviteService as InviteServicePublic) }
        }
    }
}

private fun Route.authedRpc(services: RpcServices) {
    rpc("/api/rpc/authed") {
        rpcConfig { serialization { json(contractJson) } }
        // scanFull() is ROOT/ADMIN-gated inside ScannerServiceImpl, so the registration binds
        // the caller's principal like every other authed service.
        // Streaming services pass a per-connection liveness predicate so the guard severs a revoked
        // stream mid-flight (C2). The gate reads the caller's session from the same principal the
        // service is scoped to and re-checks it against sessionLiveness on a ~30s cadence.
        registerScoped<ScannerService> {
            guard(
                (services.scannerService as ScannerServiceImpl).copyWith(it),
                streamLiveness(it, services.sessionLiveness),
            )
        }
        registerScoped<AuthServiceAuthed> { guard(services.authService.copyWith(it) as AuthServiceAuthed) }
        registerScoped<BookService> { guard((services.bookService as BookServiceImpl).copyWith(it)) }
        registerScoped<ContributorService> {
            guard(
                (services.contributorService as ContributorServiceImpl).copyWith(it),
            )
        }
        registerScoped<SeriesService> { guard((services.seriesService as SeriesServiceImpl).copyWith(it)) }
        registerScoped<PlaybackService> { guard((services.playbackService as PlaybackServiceImpl).copyWith(it)) }
        registerScoped<PlaybackProgressService> {
            guard((services.playbackProgressService as PlaybackProgressServiceImpl).copyWith(it))
        }
        registerScoped<MetadataLookupService> {
            guard((services.metadataLookupService as MetadataLookupServiceImpl).copyWith(it))
        }
        registerScoped<SearchService> { guard((services.searchService as SearchServiceImpl).copyWith(it)) }
        registerScoped<LibraryAdminService> {
            guard(
                (services.libraryAdminService as LibraryAdminServiceImpl).copyWith(it),
            )
        }
        registerScoped<TagService> { guard((services.tagService as TagServiceImpl).copyWith(it)) }
        registerScoped<MoodService> { guard((services.moodService as MoodServiceImpl).copyWith(it)) }
        registerScoped<OrganizeService> { guard((services.organizeService as OrganizeServiceImpl).copyWith(it)) }
        registerScoped<GenreService> { guard((services.genreService as GenreServiceImpl).copyWith(it)) }
        registerScoped<CollectionService> { guard((services.collectionService as CollectionServiceImpl).copyWith(it)) }
        registerScoped<ShelfService> { guard((services.shelfService as ShelfServiceImpl).copyWith(it)) }
        registerScoped<ReadingOrderService> {
            guard((services.readingOrderService as ReadingOrderServiceImpl).copyWith(it))
        }
        registerScoped<SocialService> { guard((services.socialService as SocialServiceImpl).copyWith(it)) }
        registerScoped<ActivityService> { guard((services.activityService as ActivityServiceImpl).copyWith(it)) }
        registerScoped<AdminUserService> { guard((services.adminUserService as AdminUserServiceImpl).copyWith(it)) }
        registerScoped<AdminSettingsService> {
            guard(
                (services.adminSettingsService as AdminSettingsServiceImpl).copyWith(it),
            )
        }
        registerScoped<InviteService> { guard(services.inviteService.copyWith(it) as InviteService) }
        registerScoped<ProfileService> { guard((services.profileService as ProfileServiceImpl).copyWith(it)) }
        registerScoped<UserPreferencesService> {
            guard(
                (services.userPreferencesService as UserPreferencesServiceImpl).copyWith(it),
            )
        }
        registerScoped<BackupService> {
            guard(
                (services.backupService as BackupServiceImpl).copyWith(it),
                streamLiveness(it, services.sessionLiveness),
            )
        }
        registerScoped<ImportService> {
            guard(
                (services.importService as ImportServiceImpl).copyWith(it),
                streamLiveness(it, services.sessionLiveness),
            )
        }
        registerScoped<PushService> { guard((services.pushService as PushServiceImpl).copyWith(it)) }
        registerScoped<CampfireService> {
            guard(
                (services.campfireService as CampfireServiceImpl).copyWith(it),
                streamLiveness(it, services.sessionLiveness),
            )
        }
    }
}
