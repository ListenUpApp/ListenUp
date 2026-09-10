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
import com.calypsan.listenup.web.WebAppRoot
import com.calypsan.listenup.web.nav.Router
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
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
import com.calypsan.listenup.web.features.search.fixedSearch
import com.calypsan.listenup.client.presentation.search.SearchUiState
import com.calypsan.listenup.web.features.nowplaying.fixedPlayback

/**
 * The chapters workbench through its URL contract: `?tab=chapters&sel=9,10` names the selection,
 * selection changes replace the history entry, and the inspector follows a single selection.
 */
class ChaptersPaneTest :
    FunSpec({

        var originalUrl = ""

        beforeTest {
            originalUrl = window.location.pathname + window.location.search
        }

        afterTest {
            window.history.replaceState(null, "", originalUrl)
        }

        fun mountAt(path: String): Pair<HTMLElement, Router> {
            window.history.replaceState(null, "", path)
            val router = Router()
            val host = document.createElement("div") as HTMLElement
            document.body!!.appendChild(host)
            renderComposable(root = host) {
                WebAppRoot(
                    router = router,
                    openBookDetail = fixedBookDetail(readyBook()),
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
                    openLibrarySettings = fixedLibrarySettings(LibrarySettingsUiState.Loading),
                    openAdminInbox = fixedAdminInbox(),
                    openServerSettings = fixedServerSettings(),
                    openCategories = fixedCategories(),
                    openCollections = fixedCollections(),
                    openCollectionDetail = fixedCollectionDetail(),
                    openBackups = fixedBackups(),
                    openRestore = fixedRestore(),
                    openImports = fixedImports(),
                    openImportFlow = fixedImportFlow(),
                    openShelfDetail = fixedShelfDetail(),
                    openShelfEdit = fixedShelfEdit(),
                    openLibrary = fakeLibrary(),
                    openSearch = fixedSearch(SearchUiState.Idle()),
                    openNotificationBell = fixedNotificationBell(),
                    openPlayback = fixedPlayback(),
                    observeIsAdmin = { flowOf(false) },
                    observeCurrentUserId = { flowOf(null) },
                )
            }
            return host to router
        }

        test("the chapters pane is a selectable table of every chapter") {
            val (host, router) = mountAt("/book/42?tab=chapters")

            try {
                host.querySelectorAll(".tbl tbody tr").length shouldBe CHAPTER_COUNT
                host.querySelectorAll(".tbl .cbx").length shouldBeGreaterThan CHAPTER_COUNT
                host.querySelectorAll(".bulk").length shouldBe 0
            } finally {
                router.dispose()
            }
        }

        test("the selection comes from the URL") {
            val (host, router) = mountAt("/book/42?tab=chapters&sel=9,10")

            try {
                host.querySelectorAll(".tbl tbody tr.sel").length shouldBe 2
                (host.querySelector(".bulk") as HTMLElement).textContent.orEmpty() shouldContain "2 selected"
                host.querySelectorAll(".chmap i.on").length shouldBe 2
            } finally {
                router.dispose()
            }
        }

        test("toggling a row rewrites sel without growing history") {
            val (host, router) = mountAt("/book/42?tab=chapters&sel=9")
            val depth = window.history.length

            try {
                // Row 10 is index 9; its first cell is the checkbox.
                val row = host.querySelectorAll(".tbl tbody tr").item(9) as HTMLElement
                (row.querySelector("td") as HTMLElement).click()

                window.location.search shouldContain "sel=9,10"
                window.history.length shouldBe depth
            } finally {
                router.dispose()
            }
        }

        test("clear empties the selection and drops the param") {
            val (host, router) = mountAt("/book/42?tab=chapters&sel=9,10")

            try {
                (host.querySelector(".bulk-x") as HTMLElement).click()

                window.location.search shouldNotContain "sel"
                awaitFrame()
                host.querySelectorAll(".bulk").length shouldBe 0
            } finally {
                router.dispose()
            }
        }

        test("select all from the header, then again to clear") {
            val (host, router) = mountAt("/book/42?tab=chapters")

            try {
                (host.querySelector(".tbl thead th") as HTMLElement).click()
                window.location.search shouldContain "sel="
                awaitFrame()
                host.querySelectorAll(".tbl tbody tr.sel").length shouldBe CHAPTER_COUNT

                (host.querySelector(".tbl thead th") as HTMLElement).click()
                window.location.search shouldNotContain "sel"
            } finally {
                router.dispose()
            }
        }

        test("a single selection opens the inspector on that chapter") {
            val (host, router) = mountAt("/book/42?tab=chapters&sel=12")

            try {
                val inspector = host.querySelector(".bd-side") as HTMLElement
                inspector.textContent.orEmpty() shouldContain "Chapter 12"
                inspector.querySelectorAll(".meta-r").length shouldBeGreaterThan 2
            } finally {
                router.dispose()
            }
        }

        test("a multi-selection keeps the inspector honest") {
            val (host, router) = mountAt("/book/42?tab=chapters&sel=9,10,11")

            try {
                (host.querySelector(".bd-side") as HTMLElement)
                    .textContent
                    .orEmpty() shouldContain "3 chapters selected"
            } finally {
                router.dispose()
            }
        }

        test("the chapter map draws one segment per chapter") {
            val (host, router) = mountAt("/book/42?tab=chapters")

            try {
                host.querySelectorAll(".chmap i").length shouldBe CHAPTER_COUNT
            } finally {
                router.dispose()
            }
        }
    })

private const val CHAPTER_COUNT = 33

/** Resolves after the next animation frame — when a scheduled recomposition has applied. */
private suspend fun awaitFrame() {
    suspendCoroutine { continuation ->
        window.requestAnimationFrame { window.requestAnimationFrame { continuation.resume(Unit) } }
    }
}
