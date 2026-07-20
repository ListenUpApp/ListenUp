package com.calypsan.listenup.server

import com.calypsan.listenup.api.BackupService
import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.api.ImportService
import com.calypsan.listenup.api.InstanceService
import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.api.MoodService
import com.calypsan.listenup.api.PlaybackProgressService
import com.calypsan.listenup.api.PlaybackService
import com.calypsan.listenup.api.ProfileService
import com.calypsan.listenup.api.ScannerService
import com.calypsan.listenup.api.SearchService
import com.calypsan.listenup.api.SeriesService
import com.calypsan.listenup.api.ShelfService
import com.calypsan.listenup.api.SocialService
import com.calypsan.listenup.api.TagService
import com.calypsan.listenup.api.UserPreferencesService
import com.calypsan.listenup.api.event.ScanEvent
import com.calypsan.listenup.server.api.AdminSettingsServiceImpl
import com.calypsan.listenup.server.api.AdminUserServiceImpl
import com.calypsan.listenup.server.api.BookAccessPolicy
import com.calypsan.listenup.server.api.InviteServiceImpl
import com.calypsan.listenup.server.audio.AudioFileLocator
import com.calypsan.listenup.server.audio.AudioUrlSigner
import com.calypsan.listenup.server.audio.CoverUrlSigner
import com.calypsan.listenup.server.auth.AuthServiceImpl
import com.calypsan.listenup.server.auth.RegistrationPolicyBroadcaster
import com.calypsan.listenup.server.auth.SessionService
import com.calypsan.listenup.server.settings.ServerSettingsRepository
import com.calypsan.listenup.server.auth.UserRoleLookup
import com.calypsan.listenup.server.cover.CoverResponder
import com.calypsan.listenup.server.db.sqldelight.ListenUpDatabase
import com.calypsan.listenup.server.db.sqldelight.suspendTransaction
import com.calypsan.listenup.server.document.DocumentFileLocator
import com.calypsan.listenup.server.media.ImageStore
import com.calypsan.listenup.server.plugins.JWT_PROVIDER
import com.calypsan.listenup.server.routes.RpcServices
import com.calypsan.listenup.server.routes.adminInviteRoutes
import com.calypsan.listenup.server.routes.adminRoutes
import com.calypsan.listenup.server.routes.adminUserRoutes
import com.calypsan.listenup.server.routes.audioRoutes
import com.calypsan.listenup.server.routes.authRoutes
import com.calypsan.listenup.server.routes.backupRoutes
import com.calypsan.listenup.server.routes.bookRoutes
import com.calypsan.listenup.server.routes.collectionAdminRoutes
import com.calypsan.listenup.server.routes.collectionRoutes
import com.calypsan.listenup.server.routes.contributorRoutes
import com.calypsan.listenup.server.routes.coverCastRoutes
import com.calypsan.listenup.server.routes.genreRoutes
import com.calypsan.listenup.server.routes.healthRoutes
import com.calypsan.listenup.server.routes.importRoutes
import com.calypsan.listenup.server.routes.instanceRoutes
import com.calypsan.listenup.server.routes.libraryAdminRoutes
import com.calypsan.listenup.server.routes.metadataImageRoutes
import com.calypsan.listenup.server.routes.metadataRoutes
import com.calypsan.listenup.server.routes.playbackProgressRoutes
import com.calypsan.listenup.server.routes.playbackRoutes
import com.calypsan.listenup.server.routes.profileRoutes
import com.calypsan.listenup.server.routes.publicInviteRoutes
import com.calypsan.listenup.server.routes.registrationPolicyRoutes
import com.calypsan.listenup.server.routes.rpcRoutes
import com.calypsan.listenup.server.routes.scannerRoutes
import com.calypsan.listenup.server.routes.searchRoutes
import com.calypsan.listenup.server.routes.seriesRoutes
import com.calypsan.listenup.server.routes.sseRoutes
import com.calypsan.listenup.server.routes.tagRoutes
import com.calypsan.listenup.server.services.ContributorRepository
import com.calypsan.listenup.server.services.PublicProfileMaintainer
import com.calypsan.listenup.server.services.SearchReindexService
import com.calypsan.listenup.server.services.SeriesRepository
import com.calypsan.listenup.server.services.StatsRecorder
import com.calypsan.listenup.server.sync.syncRoutes
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.io.files.Path
import org.koin.ktor.ext.get as koinGet
import org.koin.ktor.ext.inject

/**
 * Mounts every HTTP + RPC route on the application. Re-injects its own service dependencies (the
 * same idiom as [installDependencies] / [backfillPublicProfiles]) so [module] stays a high-level
 * lifecycle script rather than a flat wall of `by inject` declarations.
 *
 * @param homeDir the resolved ListenUp home dir, passed through from [module] for the metadata-image
 *   route (it is resolved once at boot and not held in Koin).
 */
internal fun Application.installAppRoutes(homeDir: Path) {
    val authService by inject<AuthServiceImpl>()
    val adminUserService by inject<AdminUserServiceImpl>()
    val adminSettingsService by inject<AdminSettingsServiceImpl>()
    val inviteService by inject<InviteServiceImpl>()
    val instanceService by inject<InstanceService>()
    val registrationPolicyBroadcaster by inject<RegistrationPolicyBroadcaster>()
    val serverSettings by inject<ServerSettingsRepository>()
    val scannerService by inject<ScannerService>()
    val eventBus by inject<SharedFlow<ScanEvent>>()
    val bookService by inject<BookService>()
    val contributorService by inject<ContributorService>()
    val seriesService by inject<SeriesService>()
    val coverResponder by inject<CoverResponder>()
    val documentFileLocator by inject<DocumentFileLocator>()
    val bookAccessPolicy by inject<BookAccessPolicy>()
    val playbackService by inject<PlaybackService>()
    val playbackProgressService by inject<PlaybackProgressService>()
    val statsRecorder by inject<StatsRecorder>()
    val searchReindexService by inject<SearchReindexService>()
    val audioFileLocator by inject<AudioFileLocator>()
    val audioUrlSigner by inject<AudioUrlSigner>()
    val coverUrlSigner by inject<CoverUrlSigner>()
    val contributorRepository by inject<ContributorRepository>()
    val seriesRepository by inject<SeriesRepository>()
    val imageStorage by inject<com.calypsan.listenup.server.metadata.ImageStorage>()
    val metadataLookupService by inject<MetadataLookupService>()
    val searchService by inject<SearchService>()
    val libraryAdminService by inject<LibraryAdminService>()
    val tagService by inject<TagService>()
    val moodService by inject<MoodService>()
    val genreService by inject<GenreService>()
    val collectionService by inject<CollectionService>()
    val shelfService by inject<ShelfService>()
    val socialService by inject<SocialService>()
    val profileService by inject<ProfileService>()
    val userPreferencesService by inject<UserPreferencesService>()
    val backupService by inject<BackupService>()
    val importService by inject<ImportService>()
    val backupPaths by inject<com.calypsan.listenup.server.backup.BackupPaths>()
    val backupArchive by inject<com.calypsan.listenup.server.backup.BackupArchive>()
    val importPaths by inject<com.calypsan.listenup.server.absimport.ImportPaths>()
    val avatarImageStore by inject<ImageStore>()
    val publicProfileMaintainer by inject<PublicProfileMaintainer>()
    val sql by inject<ListenUpDatabase>()
    val audioRoleLookup by inject<UserRoleLookup>()
    val sessionService by inject<SessionService>()
    val rpcServices = rpcServiceBundle()

    routing {
        healthRoutes()
        instanceRoutes(instanceService)
        sseRoutes()
        authRoutes(authService)
        publicInviteRoutes(inviteService)
        registrationPolicyRoutes(registrationPolicyBroadcaster) { serverSettings.registrationPolicy() }
        rpcRoutes(rpcServices)
        authenticate(JWT_PROVIDER) {
            // C2: pass the session-liveness probe so a revoked session's LIVE firehose is severed
            // within ~30s, not just blocked at the next reconnect.
            syncRoutes(sessionLiveness = sessionService::isLive)
            adminUserRoutes(adminUserService)
            adminInviteRoutes(inviteService)
            libraryAdminRoutes(libraryAdminService)
            bookRoutes(bookService, coverResponder, bookAccessPolicy, documentFileLocator)
            contributorRoutes(contributorService, homeDir, imageStorage)
            seriesRoutes(seriesService, homeDir, imageStorage)
            playbackRoutes(playbackService)
            playbackProgressRoutes(playbackProgressService, bookAccessPolicy)
            adminRoutes(statsRecorder, searchReindexService)
            metadataImageRoutes(contributorRepository, seriesRepository, homeDir)
            metadataRoutes(metadataLookupService)
            searchRoutes(searchService)
            tagRoutes(tagService, bookAccessPolicy)
            genreRoutes(genreService)
            collectionRoutes(collectionService)
            collectionAdminRoutes(collectionService)
            profileRoutes(sql, avatarImageStore, publicProfileMaintainer)
            backupRoutes(backupPaths, backupArchive)
            importRoutes(importPaths)
            scannerRoutes(scannerService, eventBus)
        }
        audioRoutes(audioFileLocator, audioUrlSigner, audioRoleLookup, bookAccessPolicy)
        coverCastRoutes(coverResponder, coverUrlSigner, audioRoleLookup, bookAccessPolicy)
    }
}

/**
 * Resolves the [RpcServices] the kRPC mount registers from the Koin graph. Each service is fetched
 * by the exact type it is registered under — `*Impl` for the four bound as their concrete class,
 * the interface for the rest — matching how [installAppRoutes] injects them for the REST routes.
 */
private fun Application.rpcServiceBundle(): RpcServices =
    RpcServices(
        authService = koinGet<AuthServiceImpl>(),
        // Session-liveness probe for the C2 streaming gate — the same lookup the JWT auth wall uses.
        sessionLiveness = koinGet<SessionService>()::isLive,
        instanceService = koinGet<InstanceService>(),
        scannerService = koinGet<ScannerService>(),
        bookService = koinGet<BookService>(),
        contributorService = koinGet<ContributorService>(),
        seriesService = koinGet<SeriesService>(),
        playbackService = koinGet<PlaybackService>(),
        playbackProgressService = koinGet<PlaybackProgressService>(),
        metadataLookupService = koinGet<MetadataLookupService>(),
        searchService = koinGet<SearchService>(),
        libraryAdminService = koinGet<LibraryAdminService>(),
        tagService = koinGet<TagService>(),
        moodService = koinGet<MoodService>(),
        genreService = koinGet<GenreService>(),
        collectionService = koinGet<CollectionService>(),
        shelfService = koinGet<ShelfService>(),
        socialService = koinGet<SocialService>(),
        adminUserService = koinGet<AdminUserServiceImpl>(),
        adminSettingsService = koinGet<AdminSettingsServiceImpl>(),
        inviteService = koinGet<InviteServiceImpl>(),
        profileService = koinGet<ProfileService>(),
        userPreferencesService = koinGet<UserPreferencesService>(),
        backupService = koinGet<BackupService>(),
        importService = koinGet<ImportService>(),
    )
