package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.web.NoCoverThemes
import com.calypsan.listenup.web.WebGraph
import com.calypsan.listenup.web.features.discover.fixedActivityFeed
import com.calypsan.listenup.web.features.admin.fixedInboxBadge
import com.calypsan.listenup.web.features.admin.fixedRestrictedBooks
import androidx.compose.runtime.Composition
import com.calypsan.listenup.web.features.hardcover.fixedBookHardcover
import com.calypsan.listenup.web.features.hardcover.fixedHardcoverMatch
import com.calypsan.listenup.web.features.licences.fixedLicences
import com.calypsan.listenup.web.features.licences.OpenLicences
import com.calypsan.listenup.web.features.licences.LicencesUiState
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
import com.calypsan.listenup.web.features.hardcover.fixedHardcover
import com.calypsan.listenup.web.features.settings.fixedSettings
import com.calypsan.listenup.web.features.shelf.fixedShelfDetail
import com.calypsan.listenup.web.features.shelf.fixedShelfEdit
import com.calypsan.listenup.web.features.discover.fixedDiscover
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.web.features.home.fixedHome
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorUiState
import com.calypsan.listenup.web.features.bookedit.fixedBookEdit
import com.calypsan.listenup.web.features.chaptereditor.fixedChapterEditor
import com.calypsan.listenup.web.features.match.fixedMatchDetails
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
import com.calypsan.listenup.client.presentation.contributordetail.ContributorBooksUiState
import com.calypsan.listenup.web.features.contributordetail.fixedContributorBooks
import com.calypsan.listenup.web.features.contributordetail.fixedContributorDetail
import com.calypsan.listenup.web.features.contributoredit.fixedContributorEdit
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
import com.calypsan.listenup.client.presentation.bookdetail.BookRatingsUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookReadersUiState
import com.calypsan.listenup.client.presentation.search.SeeAllSearchUiState
import com.calypsan.listenup.web.features.ratings.fixedBookRatings
import com.calypsan.listenup.web.features.readers.fixedBookReaders
import com.calypsan.listenup.web.features.search.fixedSeeAll
import com.calypsan.listenup.client.presentation.admin.CreateInviteUiState
import com.calypsan.listenup.client.presentation.admin.UserDetailUiState
import com.calypsan.listenup.web.features.admin.fixedCreateInvite
import com.calypsan.listenup.web.features.admin.fixedUserDetail
import com.calypsan.listenup.web.features.admin.AdminSessions
import com.calypsan.listenup.client.presentation.admin.OrganizeSettingsUiState
import com.calypsan.listenup.client.presentation.admin.upload.UploadBooksUiState
import com.calypsan.listenup.web.features.admin.fixedOrganize
import com.calypsan.listenup.web.features.admin.fixedUpload
import com.calypsan.listenup.web.features.sync.fixedDeadLetters
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import com.calypsan.listenup.web.features.shelf.fixedMyShelves

/**
 * Book Detail through the URL contract: `/book/{id}` names the book and is one page; the chapter map and the files are
 * `/book/{id}/map` and `/book/{id}/files`; an old `?tab=` link redirects without growing history; and the breadcrumb
 * is a real navigation.
 */
class BookDetailTest :
    FunSpec({

        // Every WebAppRoot owns a window-level keydown listener (the command palette's). Left
        // undisposed, an orphan answers a LATER spec's ⌘K by opening a modal nobody closes, and
        // that modal makes the whole document inert for every spec after it.
        val compositions = mutableListOf<Composition>()
        var originalUrl = ""

        beforeTest {
            originalUrl = window.location.pathname + window.location.search
        }

        afterTest {
            window.history.replaceState(null, "", originalUrl)
            disposeAll(compositions)
        }

        fun mountAt(
            path: String,
            source: OpenBookDetail = fixedBookDetail(readyBook()),
        ): Pair<HTMLElement, Router> {
            window.history.replaceState(null, "", path)
            val router = Router()
            val host = document.createElement("div") as HTMLElement
            document.body!!.appendChild(host)
            compositions +=
                renderComposable(root = host) {
                    WebAppRoot(
                        router = router,
                        graph =
                            WebGraph(
                                openBookDetail = source,
                                openBookEdit = fixedBookEdit(BookEditUiState()),
                                openChapterEditor = fixedChapterEditor(ChapterEditorUiState.Loading),
                                matchDetails = fixedMatchDetails(),
                                openContributorDetail = fixedContributorDetail(ContributorDetailUiState.Loading),
                                openContributorBooks = fixedContributorBooks(ContributorBooksUiState.Loading),
                                openContributorEdit = fixedContributorEdit(ContributorEditUiState()),
                                openSeriesDetail = fixedSeriesDetail(SeriesDetailUiState.Loading),
                                openSeriesEdit = fixedSeriesEdit(SeriesEditUiState()),
                                openNotifications = fixedNotifications(NotificationsUiState.Empty),
                                openNotificationPrefs = fixedNotificationPrefs(NotificationPrefsUiState.Loading),
                                openProfile = fixedProfile(UserProfileUiState.Loading),
                                openEditProfile = fixedEditProfile(EditProfileUiState.Loading),
                                openHome = fixedHome(HomeUiState.Loading),
                                openDiscover = fixedDiscover(),
                                openActivityFeed = fixedActivityFeed(),
                                openSettings = fixedSettings(),
                                openLicences = fixedLicences(LicencesUiState.Ready(emptyList())),
                                openDevices = fixedDevices(),
                                openHardcover = fixedHardcover(),
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
                                        upload = fixedUpload(UploadBooksUiState.Idle),
                                        organize = fixedOrganize(OrganizeSettingsUiState.Loading),
                                        inboxBadge = fixedInboxBadge(),
                                        restrictedBooks = fixedRestrictedBooks(),
                                    ),
                                openShelfDetail = fixedShelfDetail(),
                                openShelfEdit = fixedShelfEdit(),
                                openMyShelves = fixedMyShelves(),
                                openLibrary = fakeLibrary(),
                                openSearch = fixedSearch(SearchUiState.Idle()),
                                openMultiSelect = fixedMultiSelect(),
                                openBulkEdit = fixedBulkEdit(BulkEditUiState.Loading),
                                openBrowseFacet = fixedBrowseFacet(BrowseFacetUiState.Loading),
                                openGenreDestination = fixedGenreDestination(GenreDestinationUiState.Loading),
                                openBookReaders = fixedBookReaders(BookReadersUiState.Loading),
                                openBookRatings = fixedBookRatings(BookRatingsUiState.Loading),
                                openHardcoverMatch = fixedHardcoverMatch(),
                                openBookHardcover = fixedBookHardcover(),
                                openSeeAll = fixedSeeAll(SeeAllSearchUiState.Idle),
                                openDeadLetters = fixedDeadLetters(),
                                openNotificationBell = fixedNotificationBell(),
                                openPlayback = fixedPlayback(),
                                observeIsAdmin = { flowOf(false) },
                                observeCurrentUserId = { flowOf(null) },
                                observeAvatarFrame = { flowOf(null) },
                                coverThemes = NoCoverThemes,
                            ),
                        onToast = {},
                        onActionToast = { _, _, _ -> },
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

        test("there are no tabs: the page is one page") {
            val (host, router) = mountAt("/book/42")

            try {
                awaitFrame()
                host.querySelectorAll("[role=tab]").length shouldBe 0
                host.querySelector("#bd-chapters") shouldNotBe null
            } finally {
                router.dispose()
            }
        }

        test("an old ?tab=chapters link lands on the chapter map, keeping the selection") {
            val (host, router) = mountAt("/book/42?tab=chapters&sel=9,10")

            try {
                awaitFrame()
                window.location.pathname shouldBe "/book/42/map"
                window.location.search shouldContain "sel=9,10"
                window.location.search shouldNotContain "tab"
                awaitFrame()
                host.querySelector(".chmap") shouldNotBe null
            } finally {
                router.dispose()
            }
        }

        test("an old ?tab=files link lands on the files view") {
            val (host, router) = mountAt("/book/42?tab=files")

            try {
                awaitFrame()
                window.location.pathname shouldBe "/book/42/files"
                awaitFrame()
                host.querySelector(".bd-files") shouldNotBe null
            } finally {
                router.dispose()
            }
        }

        test("?tab=overview just drops the parameter") {
            val (_, router) = mountAt("/book/42?tab=overview")

            try {
                awaitFrame()
                window.location.pathname shouldBe "/book/42"
                window.location.search shouldNotContain "tab"
            } finally {
                router.dispose()
            }
        }

        test("the redirect replaces history rather than adding to it") {
            val before = window.history.length
            val (_, router) = mountAt("/book/42?tab=chapters")

            try {
                awaitFrame()
                window.location.pathname shouldBe "/book/42/map"
                window.history.length shouldBe before
            } finally {
                router.dispose()
            }
        }

        test("the Chapters header links to the chapter map, for every user") {
            val (host, router) = mountAt("/book/42", fixedBookDetail(readyBook(canEditMetadata = false)))

            try {
                awaitFrame()
                (host.querySelector("a.bd-chmap-link") as HTMLElement).click()
                awaitFrame()
                window.location.pathname shouldBe "/book/42/map"
                awaitFrame()
                host.querySelector(".chmap") shouldNotBe null
            } finally {
                router.dispose()
            }
        }

        test("the files view is reachable from Details") {
            val (host, router) = mountAt("/book/42")

            try {
                awaitFrame()
                (host.querySelector("a.bd-files-link") as HTMLElement).click()
                awaitFrame()
                window.location.pathname shouldBe "/book/42/files"
            } finally {
                router.dispose()
            }
        }

        test("the chapter map's breadcrumb leads back to the book") {
            val (host, router) = mountAt("/book/42/map")

            try {
                awaitFrame()
                (host.querySelectorAll(".crumb a").item(1) as HTMLElement).click()
                awaitFrame()
                window.location.pathname shouldBe "/book/42"
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
                    .orEmpty() shouldContain "Couldn’t load this book"
                (host.querySelector(".bd .crumb a") != null) shouldBe true
                (host.querySelector(".bd-head") == null) shouldBe true
            } finally {
                router.dispose()
            }
        }

        test("a book still loading holds its heading's place instead of showing an empty shell") {
            val (host, router) = mountAt("/book/42", fixedBookDetail(BookDetailUiState.Loading))

            try {
                // The H1 is there from the first frame — named for a screen reader, drawn as a bar.
                val heading = host.querySelector(".bd-head h1.page-t") as HTMLElement
                heading.textContent.orEmpty() shouldContain "Book"
                heading.querySelector(".skel") shouldNotBe null
            } finally {
                router.dispose()
            }
        }

        test("the header renders the book the store returned") {
            val (host, router) = mountAt("/book/42")

            try {
                (host.querySelector(".page-t") as HTMLElement).textContent shouldBe "The Institute"
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
                    "/book/42/map",
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
                (host.querySelector(".page-t") as HTMLElement).textContent.orEmpty() shouldContain "The Institute"
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
