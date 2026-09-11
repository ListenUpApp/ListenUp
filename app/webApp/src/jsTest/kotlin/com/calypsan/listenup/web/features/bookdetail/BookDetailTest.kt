package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.web.features.admin.fixedAdmin
import com.calypsan.listenup.client.presentation.admin.LibrarySettingsUiState
import com.calypsan.listenup.web.features.admin.fixedAdminInbox
import com.calypsan.listenup.web.features.admin.fixedCategories
import com.calypsan.listenup.web.features.admin.fixedCollectionDetail
import com.calypsan.listenup.web.features.admin.fixedBackups
import com.calypsan.listenup.web.features.admin.fixedImportFlow
import com.calypsan.listenup.web.features.admin.fixedImports
import com.calypsan.listenup.web.features.admin.fixedCollections
import com.calypsan.listenup.web.features.admin.fixedRestore
import com.calypsan.listenup.web.features.admin.fixedServerSettings
import com.calypsan.listenup.web.features.admin.fixedLibrarySettings
import com.calypsan.listenup.web.features.devices.fixedDevices
import com.calypsan.listenup.web.features.settings.fixedSettings
import com.calypsan.listenup.web.features.shelf.fixedShelfDetail
import com.calypsan.listenup.web.features.shelf.fixedShelfEdit
import com.calypsan.listenup.web.features.discover.fixedDiscover
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.web.features.home.fixedHome
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorUiState
import com.calypsan.listenup.client.presentation.metadata.MetadataUiState
import com.calypsan.listenup.web.features.bookedit.fixedBookEdit
import com.calypsan.listenup.web.features.chaptereditor.fixedChapterEditor
import com.calypsan.listenup.web.features.metadata.fixedMetadata
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.web.WebAppRoot
import com.calypsan.listenup.web.nav.Router
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.browser.document
import kotlinx.coroutines.flow.flowOf
import kotlinx.browser.window
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import com.calypsan.listenup.client.presentation.contributoredit.ContributorEditUiState
import com.calypsan.listenup.client.presentation.contributormetadata.ContributorMetadataUiState
import com.calypsan.listenup.client.presentation.notifications.NotificationsUiState
import com.calypsan.listenup.client.presentation.notifications.NotificationPrefsUiState
import com.calypsan.listenup.web.features.notifications.fixedNotificationPrefs
import com.calypsan.listenup.client.presentation.profile.UserProfileUiState
import com.calypsan.listenup.client.presentation.profile.EditProfileUiState
import com.calypsan.listenup.web.features.profile.fixedEditProfile
import com.calypsan.listenup.web.features.profile.fixedProfile
import com.calypsan.listenup.web.features.notifications.fixedNotificationBell
import com.calypsan.listenup.web.features.notifications.fixedNotifications
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.client.presentation.seriesedit.SeriesEditUiState
import com.calypsan.listenup.web.features.seriesdetail.fixedSeriesDetail
import com.calypsan.listenup.web.features.seriesedit.fixedSeriesEdit
import com.calypsan.listenup.web.features.contributordetail.fixedContributorDetail
import com.calypsan.listenup.web.features.contributoredit.fixedContributorEdit
import com.calypsan.listenup.web.features.contributormetadata.fixedContributorMetadata
import com.calypsan.listenup.web.features.contributors.fixedContributors
import com.calypsan.listenup.web.features.library.fakeLibrary
import com.calypsan.listenup.web.features.books.fixedMultiSelect
import com.calypsan.listenup.web.features.bulkedit.fixedBulkEdit
import com.calypsan.listenup.client.presentation.bulkedit.BulkEditUiState
import com.calypsan.listenup.web.features.search.fixedSearch
import com.calypsan.listenup.client.presentation.search.SearchUiState
import com.calypsan.listenup.web.features.nowplaying.fixedPlayback
import com.calypsan.listenup.client.presentation.browsefacet.BrowseFacetUiState
import com.calypsan.listenup.client.presentation.genredestination.GenreDestinationUiState
import com.calypsan.listenup.web.features.browse.fixedBrowseFacet
import com.calypsan.listenup.web.features.browse.fixedGenreDestination
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersUiState
import com.calypsan.listenup.client.presentation.search.SeeAllSearchUiState
import com.calypsan.listenup.web.features.readers.fixedBookReaders
import com.calypsan.listenup.web.features.search.fixedSeeAll
import com.calypsan.listenup.client.presentation.admin.CreateInviteUiState
import com.calypsan.listenup.client.presentation.admin.UserDetailUiState
import com.calypsan.listenup.web.features.admin.fixedCreateInvite
import com.calypsan.listenup.web.features.admin.fixedUserDetail
import com.calypsan.listenup.web.features.admin.AdminSessions

/**
 * Book Detail through the URL contract: `/book/{id}?tab=…` names the book and the pane, pane
 * switches replace rather than push (Back leaves the page, not the pane), and the breadcrumb is
 * a real navigation.
 */
class BookDetailTest :
    FunSpec({

        var originalUrl = ""

        beforeTest {
            originalUrl = window.location.pathname + window.location.search
        }

        afterTest {
            window.history.replaceState(null, "", originalUrl)
        }

        fun mountAt(
            path: String,
            source: OpenBookDetail = fixedBookDetail(readyBook()),
        ): Pair<HTMLElement, Router> {
            window.history.replaceState(null, "", path)
            val router = Router()
            val host = document.createElement("div") as HTMLElement
            document.body!!.appendChild(host)
            renderComposable(root = host) {
                WebAppRoot(
                    router = router,
                    openBookDetail = source,
                    openBookEdit = fixedBookEdit(BookEditUiState()),
                    openChapterEditor = fixedChapterEditor(ChapterEditorUiState.Loading),
                    openMetadata = fixedMetadata(MetadataUiState.Idle()),
                    openContributorDetail = fixedContributorDetail(ContributorDetailUiState.Loading),
                    openContributorEdit = fixedContributorEdit(ContributorEditUiState()),
                    openContributorMetadata = fixedContributorMetadata(ContributorMetadataUiState.Idle()),
                    openSeriesDetail = fixedSeriesDetail(SeriesDetailUiState.Loading),
                    openSeriesEdit = fixedSeriesEdit(SeriesEditUiState()),
                    openNotifications = fixedNotifications(NotificationsUiState.Empty),
                    openNotificationPrefs = fixedNotificationPrefs(NotificationPrefsUiState.Loading),
                    openProfile = fixedProfile(UserProfileUiState.Loading),
                    openEditProfile = fixedEditProfile(EditProfileUiState.Loading),
                    openContributors = fixedContributors(emptyList()),
                    openHome = fixedHome(HomeUiState.Loading),
                    openDiscover = fixedDiscover(),
                    openSettings = fixedSettings(),
                    openDevices = fixedDevices(),
                    openAdmin = fixedAdmin(),
                    admin =
                        AdminSessions(
                            librarySettings = fixedLibrarySettings(LibrarySettingsUiState.Loading),
                            inbox = fixedAdminInbox(),
                            serverSettings = fixedServerSettings(),
                            categories = fixedCategories(),
                            collections = fixedCollections(),
                            collectionDetail = fixedCollectionDetail(),
                            backups = fixedBackups(),
                            restore = fixedRestore(),
                            imports = fixedImports(),
                            importFlow = fixedImportFlow(),
                            createInvite = fixedCreateInvite(CreateInviteUiState.Ready()),
                            userDetail = fixedUserDetail(UserDetailUiState.Loading),
                        ),
                    openShelfDetail = fixedShelfDetail(),
                    openShelfEdit = fixedShelfEdit(),
                    openLibrary = fakeLibrary(),
                    openSearch = fixedSearch(SearchUiState.Idle()),
                    openMultiSelect = fixedMultiSelect(),
                    openBulkEdit = fixedBulkEdit(BulkEditUiState.Loading),
                    openBrowseFacet = fixedBrowseFacet(BrowseFacetUiState.Loading),
                    openGenreDestination = fixedGenreDestination(GenreDestinationUiState.Loading),
                    openBookReaders = fixedBookReaders(BookReadersUiState.Loading),
                    openSeeAll = fixedSeeAll(SeeAllSearchUiState.Idle),
                    onToast = {},
                    openNotificationBell = fixedNotificationBell(),
                    openPlayback = fixedPlayback(),
                    observeIsAdmin = { flowOf(false) },
                    observeCurrentUserId = { flowOf(null) },
                )
            }
            return host to router
        }

        test("a book deep link renders the detail page with Library active") {
            val (host, router) = mountAt("/book/42")

            try {
                (host.querySelector(".bd") != null) shouldBe true
                (host.querySelector(".nav-i.on") as HTMLElement)
                    .textContent
                    .orEmpty() shouldContain "Library"
            } finally {
                router.dispose()
            }
        }

        test("the pane comes from the URL") {
            val (host, router) = mountAt("/book/42?tab=chapters")

            try {
                (host.querySelector(".tab.on") as HTMLElement)
                    .textContent
                    .orEmpty() shouldContain "Chapters"
            } finally {
                router.dispose()
            }
        }

        test("switching panes rewrites the URL without growing history") {
            // A pane switch is shareable state, so it belongs in the URL — but Back should leave
            // the page, not unwind every pane the user looked at.
            val (host, router) = mountAt("/book/42")
            val depth = window.history.length

            try {
                val tabs = host.querySelectorAll(".tab")
                (tabs.item(1) as HTMLElement).click()

                window.location.search shouldContain "tab=chapters"
                window.history.length shouldBe depth
                awaitFrame()
                (host.querySelector(".tab.on") as HTMLElement)
                    .textContent
                    .orEmpty() shouldContain "Chapters"
            } finally {
                router.dispose()
            }
        }

        test("the breadcrumb returns to the library") {
            val (host, router) = mountAt("/book/42")

            try {
                (host.querySelector(".crumb a") as HTMLElement).click()

                window.location.pathname shouldBe "/library"
                awaitFrame()
                (host.querySelector(".bd") == null) shouldBe true
            } finally {
                router.dispose()
            }
        }

        test("the page asks the store for the book named in the URL") {
            var asked: String? = null
            val (_, router) =
                mountAt("/book/the-institute") { bookId ->
                    asked = bookId
                    fixedBookDetail(readyBook())(bookId)
                }

            try {
                asked shouldBe "the-institute"
            } finally {
                router.dispose()
            }
        }

        test("a book the store doesn't have says so, and still offers the way back") {
            // The honest state for this client today: no sync exists yet, so a deep link into an
            // empty browser store lands here. It must not look like a broken page.
            val (host, router) =
                mountAt("/book/42", fixedBookDetail(BookDetailUiState.Error(BookError.NotFound())))

            try {
                (host.querySelector(".bd .empty") as HTMLElement)
                    .textContent
                    .orEmpty() shouldContain "Not in this browser's library"
                (host.querySelector(".bd .crumb a") != null) shouldBe true
                (host.querySelector(".bd-t") == null) shouldBe true
            } finally {
                router.dispose()
            }
        }

        test("a book still loading says that instead of showing an empty shell") {
            val (host, router) = mountAt("/book/42", fixedBookDetail(BookDetailUiState.Loading))

            try {
                (host.querySelector(".bd .empty") as HTMLElement)
                    .textContent
                    .orEmpty() shouldContain "Loading"
            } finally {
                router.dispose()
            }
        }

        test("the header renders the book the store returned") {
            val (host, router) = mountAt("/book/42")

            try {
                (host.querySelector(".bd-t") as HTMLElement).textContent shouldBe "The Institute"
                (host.querySelector(".bd-by") as HTMLElement)
                    .textContent
                    .orEmpty() shouldContain "Stephen King · read by Santino Fontana"
            } finally {
                router.dispose()
            }
        }

        test("a book with no chapter marks says so rather than drawing an empty table") {
            val (host, router) =
                mountAt(
                    "/book/42?tab=chapters",
                    fixedBookDetail(readyBook(chapters = emptyList())),
                )

            try {
                (host.querySelector(".chmap") == null) shouldBe true
                (host.querySelector(".bd .tblwrap") == null) shouldBe true
                (host.querySelector(".bd section") as HTMLElement)
                    .textContent
                    .orEmpty() shouldContain "no chapter marks"
            } finally {
                router.dispose()
            }
        }

        test("the overview pane lays out the book's details") {
            val (host, router) = mountAt("/book/42")

            try {
                host.querySelectorAll(".bd .meta-r").length shouldBeGreaterThanOrEqual 4
                (host.querySelector(".bd-side") != null) shouldBe true
                (host.querySelector(".bd-t") as HTMLElement).textContent.orEmpty() shouldContain "The Institute"
            } finally {
                router.dispose()
            }
        }
    })

/** Resolves after the next animation frame — when a scheduled recomposition has applied. */
private suspend fun awaitFrame() {
    suspendCoroutine { continuation ->
        window.requestAnimationFrame { window.requestAnimationFrame { continuation.resume(Unit) } }
    }
}
