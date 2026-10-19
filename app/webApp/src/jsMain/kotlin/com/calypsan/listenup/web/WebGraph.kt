package com.calypsan.listenup.web

import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.domain.repository.UserProfileRepository
import com.calypsan.listenup.client.domain.repository.UserRepository
import com.calypsan.listenup.web.features.admin.AdminSessions
import com.calypsan.listenup.web.features.admin.OpenAdmin
import com.calypsan.listenup.web.features.admin.graphAdmin
import com.calypsan.listenup.web.features.admin.graphAdminInbox
import com.calypsan.listenup.web.features.admin.graphBackups
import com.calypsan.listenup.web.features.admin.graphCategories
import com.calypsan.listenup.web.features.admin.graphCollectionDetail
import com.calypsan.listenup.web.features.admin.graphCollections
import com.calypsan.listenup.web.features.admin.graphCreateInvite
import com.calypsan.listenup.web.features.admin.graphImportFlow
import com.calypsan.listenup.web.features.admin.graphImports
import com.calypsan.listenup.web.features.admin.graphInboxBadge
import com.calypsan.listenup.web.features.admin.graphLibrarySettings
import com.calypsan.listenup.web.features.admin.graphOrganize
import com.calypsan.listenup.web.features.admin.graphRestore
import com.calypsan.listenup.web.features.admin.graphRestrictedBooks
import com.calypsan.listenup.web.features.admin.graphServerSettings
import com.calypsan.listenup.web.features.admin.graphUpload
import com.calypsan.listenup.web.features.admin.graphUserDetail
import com.calypsan.listenup.web.features.bookdetail.OpenBookDetail
import com.calypsan.listenup.web.features.bookdetail.graphBookDetail
import com.calypsan.listenup.web.features.bookedit.OpenBookEdit
import com.calypsan.listenup.web.features.bookedit.graphBookEdit
import com.calypsan.listenup.web.features.books.OpenMultiSelect
import com.calypsan.listenup.web.features.books.graphMultiSelect
import com.calypsan.listenup.web.features.browse.OpenBrowseFacet
import com.calypsan.listenup.web.features.browse.OpenGenreDestination
import com.calypsan.listenup.web.features.browse.graphBrowseFacet
import com.calypsan.listenup.web.features.browse.graphGenreDestination
import com.calypsan.listenup.web.features.bulkedit.OpenBulkEdit
import com.calypsan.listenup.web.features.bulkedit.graphBulkEdit
import com.calypsan.listenup.web.features.chaptereditor.OpenChapterEditor
import com.calypsan.listenup.web.features.chaptereditor.graphChapterEditor
import com.calypsan.listenup.web.features.contributordetail.OpenContributorBooks
import com.calypsan.listenup.web.features.contributordetail.OpenContributorDetail
import com.calypsan.listenup.web.features.contributordetail.graphContributorBooks
import com.calypsan.listenup.web.features.contributordetail.graphContributorDetail
import com.calypsan.listenup.web.features.contributoredit.OpenContributorEdit
import com.calypsan.listenup.web.features.contributoredit.graphContributorEdit
import com.calypsan.listenup.web.features.devices.OpenDevices
import com.calypsan.listenup.web.features.devices.graphDevices
import com.calypsan.listenup.web.features.discover.OpenDiscover
import com.calypsan.listenup.web.features.discover.graphDiscover
import com.calypsan.listenup.web.features.hardcover.OpenBookHardcover
import com.calypsan.listenup.web.features.hardcover.OpenHardcover
import com.calypsan.listenup.web.features.hardcover.OpenHardcoverMatch
import com.calypsan.listenup.web.features.hardcover.graphBookHardcover
import com.calypsan.listenup.web.features.hardcover.graphHardcover
import com.calypsan.listenup.web.features.hardcover.graphHardcoverMatch
import com.calypsan.listenup.web.features.home.OpenHome
import com.calypsan.listenup.web.features.home.graphHome
import com.calypsan.listenup.web.features.library.OpenLibrary
import com.calypsan.listenup.web.features.library.graphLibrary
import com.calypsan.listenup.web.features.licences.OpenLicences
import com.calypsan.listenup.web.features.licences.graphLicences
import com.calypsan.listenup.web.features.match.MatchDetailsGraph
import com.calypsan.listenup.web.features.match.graphMatchDetails
import com.calypsan.listenup.web.features.notifications.OpenNotificationBell
import com.calypsan.listenup.web.features.notifications.OpenNotificationPrefs
import com.calypsan.listenup.web.features.notifications.OpenNotifications
import com.calypsan.listenup.web.features.notifications.graphNotificationBell
import com.calypsan.listenup.web.features.notifications.graphNotificationPrefs
import com.calypsan.listenup.web.features.notifications.graphNotifications
import com.calypsan.listenup.web.features.nowplaying.OpenPlayback
import com.calypsan.listenup.web.features.nowplaying.graphPlayback
import com.calypsan.listenup.web.features.profile.OpenEditProfile
import com.calypsan.listenup.web.features.profile.OpenProfile
import com.calypsan.listenup.web.features.profile.graphEditProfile
import com.calypsan.listenup.web.features.profile.graphProfile
import com.calypsan.listenup.web.features.ratings.OpenBookRatings
import com.calypsan.listenup.web.features.ratings.graphBookRatings
import com.calypsan.listenup.web.features.readers.OpenBookReaders
import com.calypsan.listenup.web.features.readers.graphBookReaders
import com.calypsan.listenup.web.features.search.OpenSearch
import com.calypsan.listenup.web.features.search.OpenSeeAll
import com.calypsan.listenup.web.features.search.graphSearch
import com.calypsan.listenup.web.features.search.graphSeeAll
import com.calypsan.listenup.web.features.seriesdetail.OpenSeriesDetail
import com.calypsan.listenup.web.features.seriesdetail.graphSeriesDetail
import com.calypsan.listenup.web.features.seriesedit.OpenSeriesEdit
import com.calypsan.listenup.web.features.seriesedit.graphSeriesEdit
import com.calypsan.listenup.web.features.settings.OpenSettings
import com.calypsan.listenup.web.features.settings.graphSettings
import com.calypsan.listenup.web.features.shelf.OpenShelfDetail
import com.calypsan.listenup.web.features.shelf.OpenShelfEdit
import com.calypsan.listenup.web.features.shelf.OpenMyShelves
import com.calypsan.listenup.web.features.shelf.graphMyShelves
import com.calypsan.listenup.web.features.shelf.graphShelfDetail
import com.calypsan.listenup.web.features.shelf.graphShelfEdit
import com.calypsan.listenup.web.features.sync.OpenDeadLetters
import com.calypsan.listenup.web.features.sync.graphDeadLetters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.Koin

/**
 * Everything the signed-in shell opens: one member per page or shell service.
 *
 * No member has a default, on purpose. A defaulted fake compiles clean and leaves a page silently
 * inert, and nothing looks broken from the outside:
 * - [openPlayback] is scoped to the shell rather than a route (what is playing outlives the page it
 *   was started from); a spec passes `fixedPlayback`.
 * - [observeIsAdmin] gates the sidebar's Admin entry; a defaulted `flowOf(false)` would hide Admin
 *   from every admin.
 * - [openSearch] and [openHome] back the sidebar's Search item and the root route; a defaulted fake
 *   would leave them landing on a page that never does anything.
 *
 * Production passes [graphWeb]; a spec passes the `fixed...` doubles.
 */
class WebGraph(
    val openBookDetail: OpenBookDetail,
    val openBookEdit: OpenBookEdit,
    val openChapterEditor: OpenChapterEditor,
    val matchDetails: MatchDetailsGraph,
    val openContributorDetail: OpenContributorDetail,
    val openContributorBooks: OpenContributorBooks,
    val openContributorEdit: OpenContributorEdit,
    val openSeriesDetail: OpenSeriesDetail,
    val openSeriesEdit: OpenSeriesEdit,
    val openNotifications: OpenNotifications,
    val openNotificationPrefs: OpenNotificationPrefs,
    val openLicences: OpenLicences,
    val openProfile: OpenProfile,
    val openEditProfile: OpenEditProfile,
    val openHome: OpenHome,
    val openDiscover: OpenDiscover,
    val openSettings: OpenSettings,
    val openDevices: OpenDevices,
    val openHardcover: OpenHardcover,
    val openAdmin: OpenAdmin,
    val admin: AdminSessions,
    val openShelfDetail: OpenShelfDetail,
    val openShelfEdit: OpenShelfEdit,
    val openMyShelves: OpenMyShelves,
    val openLibrary: OpenLibrary,
    val openSearch: OpenSearch,
    val openMultiSelect: OpenMultiSelect,
    val openBulkEdit: OpenBulkEdit,
    val openBrowseFacet: OpenBrowseFacet,
    val openGenreDestination: OpenGenreDestination,
    val openBookReaders: OpenBookReaders,
    val openBookRatings: OpenBookRatings,
    val openHardcoverMatch: OpenHardcoverMatch,
    val openBookHardcover: OpenBookHardcover,
    val openSeeAll: OpenSeeAll,
    val openDeadLetters: OpenDeadLetters,
    val openNotificationBell: OpenNotificationBell,
    val openPlayback: OpenPlayback,
    val observeIsAdmin: () -> Flow<Boolean>,
    val observeCurrentUserId: () -> Flow<String?>,
    /**
     * Each user's avatar frame, from their `public_profiles` row; provided to every avatar as
     * [com.calypsan.listenup.web.design.LocalAvatarFrames]. A defaulted `flowOf(null)` would quietly
     * draw everyone's picture as the centre crop, whatever they chose.
     */
    val observeAvatarFrame: (userId: String) -> Flow<ImageFrame?>,
)

/** The production graph, built once from the client's dependency graph. */
fun graphWeb(koin: Koin): WebGraph =
    WebGraph(
        openBookDetail = graphBookDetail(koin),
        openBookEdit = graphBookEdit(koin),
        openChapterEditor = graphChapterEditor(koin),
        matchDetails = graphMatchDetails(koin),
        openContributorDetail = graphContributorDetail(koin),
        openContributorBooks = graphContributorBooks(koin),
        openContributorEdit = graphContributorEdit(koin),
        openSeriesDetail = graphSeriesDetail(koin),
        openSeriesEdit = graphSeriesEdit(koin),
        openNotifications = graphNotifications(koin),
        openNotificationPrefs = graphNotificationPrefs(koin),
        openProfile = graphProfile(koin),
        openEditProfile = graphEditProfile(koin),
        openNotificationBell = graphNotificationBell(koin),
        openLibrary = graphLibrary(koin),
        openHome = graphHome(koin),
        openDiscover = graphDiscover(koin),
        openSettings = graphSettings(koin),
        openLicences = graphLicences(),
        openDevices = graphDevices(koin),
        openHardcover = graphHardcover(koin),
        openAdmin = graphAdmin(koin),
        admin =
            AdminSessions(
                librarySettings = graphLibrarySettings(koin),
                inbox = graphAdminInbox(koin),
                serverSettings = graphServerSettings(koin),
                categories = graphCategories(koin),
                collections = graphCollections(koin),
                collectionDetail = graphCollectionDetail(koin),
                backups = graphBackups(koin),
                restore = graphRestore(koin),
                imports = graphImports(koin),
                importFlow = graphImportFlow(koin),
                createInvite = graphCreateInvite(koin),
                userDetail = graphUserDetail(koin),
                upload = graphUpload(koin),
                organize = graphOrganize(koin),
                inboxBadge = graphInboxBadge(koin),
                restrictedBooks = graphRestrictedBooks(koin),
            ),
        openShelfDetail = graphShelfDetail(koin),
        openShelfEdit = graphShelfEdit(koin),
        openMyShelves = graphMyShelves(koin),
        openSearch = graphSearch(koin),
        openMultiSelect = graphMultiSelect(koin),
        openBulkEdit = graphBulkEdit(koin),
        openBrowseFacet = graphBrowseFacet(koin),
        openGenreDestination = graphGenreDestination(koin),
        openBookReaders = graphBookReaders(koin),
        openBookRatings = graphBookRatings(koin),
        openHardcoverMatch = graphHardcoverMatch(koin),
        openBookHardcover = graphBookHardcover(koin),
        openSeeAll = graphSeeAll(koin),
        openDeadLetters = graphDeadLetters(koin),
        openPlayback = graphPlayback(koin),
        observeIsAdmin = { koin.get<UserRepository>().observeIsAdmin() },
        observeCurrentUserId = { koin.get<UserRepository>().observeCurrentUser().map { it?.id?.value } },
        observeAvatarFrame = { userId ->
            koin.get<UserProfileRepository>().observeProfile(userId).map { it?.avatarFrame }
        },
    )
