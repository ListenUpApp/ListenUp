package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.AdminSettingsService
import com.calypsan.listenup.api.AdminUserService
import com.calypsan.listenup.api.AuthServiceAuthed
import com.calypsan.listenup.api.AuthServicePublic
import com.calypsan.listenup.api.BackupService
import com.calypsan.listenup.api.BookRatingService
import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.EntityService
import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.api.HardcoverService
import com.calypsan.listenup.api.ImportService
import com.calypsan.listenup.api.InviteService
import com.calypsan.listenup.api.InviteServicePublic
import com.calypsan.listenup.api.InstanceService
import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.MatchingService
import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.api.MoodService
import com.calypsan.listenup.api.NotificationService
import com.calypsan.listenup.api.OrganizeService
import com.calypsan.listenup.api.PlaybackProgressService
import com.calypsan.listenup.api.PingService
import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.api.PushService
import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.ReadingOrderService
import com.calypsan.listenup.api.ShelfService
import com.calypsan.listenup.api.SocialService
import com.calypsan.listenup.api.SyncStreamService
import com.calypsan.listenup.api.TagService
import com.calypsan.listenup.api.UserPreferencesService
import com.calypsan.listenup.api.contractJson
import com.calypsan.listenup.server.api.AdminSettingsServiceImpl
import com.calypsan.listenup.server.api.AdminUserServiceImpl
import com.calypsan.listenup.server.api.BackupServiceImpl
import com.calypsan.listenup.server.api.BookRatingServiceImpl
import com.calypsan.listenup.server.api.BookServiceImpl
import com.calypsan.listenup.server.api.CollectionServiceImpl
import com.calypsan.listenup.server.api.ContributorServiceImpl
import com.calypsan.listenup.server.api.EntityServiceImpl
import com.calypsan.listenup.server.api.GenreServiceImpl
import com.calypsan.listenup.server.api.HardcoverServiceImpl
import com.calypsan.listenup.server.api.ImportServiceImpl
import com.calypsan.listenup.server.api.LibraryAdminServiceImpl
import com.calypsan.listenup.server.api.MatchingServiceImpl
import com.calypsan.listenup.server.api.MetadataLookupServiceImpl
import com.calypsan.listenup.server.api.MoodServiceImpl
import com.calypsan.listenup.server.api.NotificationServiceImpl
import com.calypsan.listenup.server.api.OrganizeServiceImpl
import com.calypsan.listenup.server.api.PlaybackProgressServiceImpl
import com.calypsan.listenup.server.api.PlaybackServiceImpl
import com.calypsan.listenup.server.api.ProfileServiceImpl
import com.calypsan.listenup.server.api.PushServiceImpl
import com.calypsan.listenup.server.api.SeriesServiceImpl
import com.calypsan.listenup.server.api.ReadingOrderServiceImpl
import com.calypsan.listenup.server.api.ShelfServiceImpl
import com.calypsan.listenup.server.api.SocialServiceImpl
import com.calypsan.listenup.server.api.TagServiceImpl
import com.calypsan.listenup.server.api.UserPreferencesServiceImpl
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.rpcguard.guard
import com.calypsan.listenup.server.scanner.ScannerServiceImpl
import com.calypsan.listenup.server.sync.SyncStreamServiceImpl
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import kotlinx.rpc.krpc.ktor.server.rpc
import kotlinx.rpc.krpc.serialization.json.json

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
        // SEC-02: bind the caller's remote host so the per-IP claim/lookup throttle inside
        // InviteServiceImpl keys on it — mirrors the AuthServicePublic binding above.
        registerService<InviteServicePublic> {
            guardedConstruction {
                guard(services.inviteService.withRemoteHost(call.request.origin.remoteHost) as InviteServicePublic)
            }
        }
    }
}

private fun Route.authedRpc(services: RpcServices) {
    rpc("/api/rpc/authed") {
        rpcConfig { serialization { json(contractJson) } }
        // scanFull() is ROOT/ADMIN-gated inside ScannerServiceImpl, so the registration binds
        // the caller's principal like every other authed service.
        // Every service gets the connection's cached session-liveness probe (`live`): the generated guard
        // refuses an ordinary call on a revoked session, and a stream re-checks it on a ~30s cadence. The
        // JWT is only verified when the socket opens, so without this a revoked socket keeps its authority.
        registerScoped<ScannerService>(services.sessionLiveness) { provider, live ->
            guard((services.scannerService as ScannerServiceImpl).copyWith(provider), live)
        }
        registerScoped<AuthServiceAuthed>(services.sessionLiveness) { provider, live ->
            guard(services.authService.copyWith(provider) as AuthServiceAuthed, live)
        }
        registerScoped<BookService>(services.sessionLiveness) { provider, live ->
            guard((services.bookService as BookServiceImpl).copyWith(provider), live)
        }
        registerScoped<ContributorService>(services.sessionLiveness) { provider, live ->
            guard((services.contributorService as ContributorServiceImpl).copyWith(provider), live)
        }
        registerScoped<SeriesService>(services.sessionLiveness) { provider, live ->
            guard((services.seriesService as SeriesServiceImpl).copyWith(provider), live)
        }
        registerScoped<EntityService>(services.sessionLiveness) { provider, live ->
            guard((services.entityService as EntityServiceImpl).copyWith(provider), live)
        }
        registerScoped<PlaybackService>(services.sessionLiveness) { provider, live ->
            guard((services.playbackService as PlaybackServiceImpl).copyWith(provider), live)
        }
        registerScoped<PlaybackProgressService>(services.sessionLiveness) { provider, live ->
            guard((services.playbackProgressService as PlaybackProgressServiceImpl).copyWith(provider), live)
        }
        registerScoped<MetadataLookupService>(services.sessionLiveness) { provider, live ->
            guard((services.metadataLookupService as MetadataLookupServiceImpl).copyWith(provider), live)
        }
        registerScoped<MatchingService>(services.sessionLiveness) { provider, live ->
            guard((services.matchingService as MatchingServiceImpl).copyWith(provider), live)
        }
        registerScoped<LibraryAdminService>(services.sessionLiveness) { provider, live ->
            guard((services.libraryAdminService as LibraryAdminServiceImpl).copyWith(provider), live)
        }
        registerScoped<TagService>(services.sessionLiveness) { provider, live ->
            guard((services.tagService as TagServiceImpl).copyWith(provider), live)
        }
        registerScoped<MoodService>(services.sessionLiveness) { provider, live ->
            guard((services.moodService as MoodServiceImpl).copyWith(provider), live)
        }
        registerScoped<BookRatingService>(services.sessionLiveness) { provider, live ->
            guard((services.bookRatingService as BookRatingServiceImpl).copyWith(provider), live)
        }
        registerScoped<OrganizeService>(services.sessionLiveness) { provider, live ->
            guard((services.organizeService as OrganizeServiceImpl).copyWith(provider), live)
        }
        registerScoped<GenreService>(services.sessionLiveness) { provider, live ->
            guard((services.genreService as GenreServiceImpl).copyWith(provider), live)
        }
        registerScoped<CollectionService>(services.sessionLiveness) { provider, live ->
            guard((services.collectionService as CollectionServiceImpl).copyWith(provider), live)
        }
        registerScoped<ShelfService>(services.sessionLiveness) { provider, live ->
            guard((services.shelfService as ShelfServiceImpl).copyWith(provider), live)
        }
        registerScoped<ReadingOrderService>(services.sessionLiveness) { provider, live ->
            guard((services.readingOrderService as ReadingOrderServiceImpl).copyWith(provider), live)
        }
        registerScoped<SocialService>(services.sessionLiveness) { provider, live ->
            guard((services.socialService as SocialServiceImpl).copyWith(provider), live)
        }
        registerScoped<AdminUserService>(services.sessionLiveness) { provider, live ->
            guard((services.adminUserService as AdminUserServiceImpl).copyWith(provider), live)
        }
        registerScoped<AdminSettingsService>(services.sessionLiveness) { provider, live ->
            guard((services.adminSettingsService as AdminSettingsServiceImpl).copyWith(provider), live)
        }
        registerScoped<InviteService>(services.sessionLiveness) { provider, live ->
            guard(services.inviteService.copyWith(provider) as InviteService, live)
        }
        registerScoped<ProfileService>(services.sessionLiveness) { provider, live ->
            guard((services.profileService as ProfileServiceImpl).copyWith(provider), live)
        }
        registerScoped<UserPreferencesService>(services.sessionLiveness) { provider, live ->
            guard((services.userPreferencesService as UserPreferencesServiceImpl).copyWith(provider), live)
        }
        registerScoped<PushService>(services.sessionLiveness) { provider, live ->
            guard((services.pushService as PushServiceImpl).copyWith(provider), live)
        }
        registerScoped<NotificationService>(services.sessionLiveness) { provider, live ->
            guard((services.notificationService as NotificationServiceImpl).copyWith(provider), live)
        }
        registerScoped<BackupService>(services.sessionLiveness) { provider, live ->
            guard((services.backupService as BackupServiceImpl).copyWith(provider), live)
        }
        registerScoped<ImportService>(services.sessionLiveness) { provider, live ->
            guard((services.importService as ImportServiceImpl).copyWith(provider), live)
        }
        registerScoped<HardcoverService>(services.sessionLiveness) { provider, live ->
            guard((services.hardcoverService as HardcoverServiceImpl).copyWith(provider), live)
        }
        registerScoped<SyncStreamService>(services.sessionLiveness) { provider, live ->
            guard((services.syncStreamService as SyncStreamServiceImpl).copyWith(provider), live)
        }
    }
}
