package com.calypsan.listenup.server.routes

import com.calypsan.listenup.api.ActivityService
import com.calypsan.listenup.api.AdminSettingsService
import com.calypsan.listenup.api.AdminUserService
import com.calypsan.listenup.api.BackupService
import com.calypsan.listenup.api.BookService
import com.calypsan.listenup.api.CampfireService
import com.calypsan.listenup.api.CollectionService
import com.calypsan.listenup.api.ContributorService
import com.calypsan.listenup.api.GenreService
import com.calypsan.listenup.api.ImportService
import com.calypsan.listenup.api.InstanceService
import com.calypsan.listenup.api.LibraryAdminService
import com.calypsan.listenup.api.MetadataLookupService
import com.calypsan.listenup.api.MoodService
import com.calypsan.listenup.api.OrganizeService
import com.calypsan.listenup.api.PlaybackProgressService
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
import com.calypsan.listenup.server.api.InviteServiceImpl
import com.calypsan.listenup.server.auth.AuthServiceImpl

/**
 * The service implementations the kRPC mount registers, bundled so [rpcRoutes] takes a single
 * parameter instead of threading 27 services through the expect/actual boundary.
 */
data class RpcServices(
    val authService: AuthServiceImpl,
    val instanceService: InstanceService,
    val scannerService: ScannerService,
    val bookService: BookService,
    val contributorService: ContributorService,
    val seriesService: SeriesService,
    val playbackService: PlaybackService,
    val playbackProgressService: PlaybackProgressService,
    val metadataLookupService: MetadataLookupService,
    val searchService: SearchService,
    val libraryAdminService: LibraryAdminService,
    val tagService: TagService,
    val moodService: MoodService,
    val organizeService: OrganizeService,
    val genreService: GenreService,
    val collectionService: CollectionService,
    val shelfService: ShelfService,
    val readingOrderService: ReadingOrderService,
    val socialService: SocialService,
    val activityService: ActivityService,
    val adminUserService: AdminUserService,
    val adminSettingsService: AdminSettingsService,
    val inviteService: InviteServiceImpl,
    val profileService: ProfileService,
    val userPreferencesService: UserPreferencesService,
    val backupService: BackupService,
    val importService: ImportService,
    val pushService: PushService,
    val campfireService: CampfireService,
)
