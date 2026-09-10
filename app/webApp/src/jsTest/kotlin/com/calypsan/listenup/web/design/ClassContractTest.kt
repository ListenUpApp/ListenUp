package com.calypsan.listenup.web.design

import com.calypsan.listenup.web.features.admin.AdminInboxPage
import com.calypsan.listenup.web.features.admin.BackupsPage
import com.calypsan.listenup.web.features.admin.ImportFlowPage
import com.calypsan.listenup.web.features.admin.ImportsPage
import com.calypsan.listenup.web.features.admin.absItem
import com.calypsan.listenup.web.features.admin.importResult
import com.calypsan.listenup.web.features.admin.readyImports
import com.calypsan.listenup.web.features.admin.review
import com.calypsan.listenup.web.features.admin.CategoriesPage
import com.calypsan.listenup.web.features.admin.RestorePage
import com.calypsan.listenup.web.features.admin.backup
import com.calypsan.listenup.web.features.admin.readyBackups
import com.calypsan.listenup.web.features.admin.restoreResult
import com.calypsan.listenup.web.features.admin.CollectionDetailPage
import com.calypsan.listenup.web.features.admin.CollectionsPage
import com.calypsan.listenup.web.features.admin.collection
import com.calypsan.listenup.web.features.admin.personFixture
import com.calypsan.listenup.web.features.admin.shareFixture
import com.calypsan.listenup.web.features.admin.readyCollections
import com.calypsan.listenup.web.features.admin.readyDetail
import com.calypsan.listenup.web.features.admin.genre
import com.calypsan.listenup.web.features.admin.node
import com.calypsan.listenup.web.features.admin.readyCategories
import com.calypsan.listenup.web.features.admin.ServerSettingsPage
import com.calypsan.listenup.web.features.admin.readyServerSettings
import com.calypsan.listenup.web.features.admin.AdminPage
import com.calypsan.listenup.web.features.admin.inboxBook
import com.calypsan.listenup.web.features.admin.readyInbox
import com.calypsan.listenup.web.features.admin.scanIssue
import com.calypsan.listenup.api.dto.backup.BackupEvent
import com.calypsan.listenup.client.presentation.admin.ABSImportListUiState
import com.calypsan.listenup.client.presentation.admin.AdminBackupUiState
import com.calypsan.listenup.client.presentation.admin.imports.BookSearchState
import com.calypsan.listenup.client.presentation.admin.imports.ImportFlowUiState
import com.calypsan.listenup.client.presentation.admin.AdminCategoriesUiState
import com.calypsan.listenup.client.presentation.admin.RestoreBackupUiState
import com.calypsan.listenup.client.presentation.admin.RestoreFromFileUiState
import com.calypsan.listenup.client.presentation.admin.AdminCollectionDetailUiState
import com.calypsan.listenup.client.presentation.admin.AdminCollectionsUiState
import com.calypsan.listenup.client.presentation.admin.AdminInboxUiState
import com.calypsan.listenup.client.presentation.admin.AdminSettingsUiState
import com.calypsan.listenup.client.presentation.admin.AdminUiState
import com.calypsan.listenup.client.domain.model.InviteInfo
import com.calypsan.listenup.client.domain.model.AdminUserInfo
import com.calypsan.listenup.api.dto.auth.UserId
import com.calypsan.listenup.api.dto.auth.PasswordResetRequest
import org.w3c.dom.HTMLDialogElement
import com.calypsan.listenup.web.features.devices.DevicesPage
import com.calypsan.listenup.web.design.ConfirmDialog
import com.calypsan.listenup.client.presentation.settings.DevicesUiState
import com.calypsan.listenup.client.presentation.settings.DeviceRow
import com.calypsan.listenup.api.error.InternalError
import com.calypsan.listenup.web.features.settings.SettingsPage
import com.calypsan.listenup.client.presentation.settings.SettingsUiState
import com.calypsan.listenup.core.BookId
import com.calypsan.listenup.core.ShelfId
import com.calypsan.listenup.client.domain.model.ShelfBook
import com.calypsan.listenup.client.domain.model.ShelfDetail
import com.calypsan.listenup.client.presentation.discover.DiscoverShelfOwner
import com.calypsan.listenup.client.presentation.discover.DiscoverShelfUi
import com.calypsan.listenup.client.presentation.discover.DiscoverShelvesUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverUserShelves
import com.calypsan.listenup.client.presentation.shelf.CreateEditShelfUiState
import com.calypsan.listenup.client.presentation.shelf.ShelfDetailUiState
import com.calypsan.listenup.web.features.home.shelf
import com.calypsan.listenup.web.features.shelf.ShelfDetailPage
import com.calypsan.listenup.web.features.shelf.ShelfEditPage
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardCategory
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardEntry
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardPeriod
import com.calypsan.listenup.client.domain.leaderboard.LeaderboardSnapshot
import com.calypsan.listenup.client.presentation.discover.ActivityFeedUiState
import com.calypsan.listenup.client.presentation.discover.ActivityUiModel
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiSession
import com.calypsan.listenup.client.presentation.discover.CurrentlyListeningUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverBooksUiState
import com.calypsan.listenup.client.presentation.discover.DiscoverUiBook
import com.calypsan.listenup.client.presentation.discover.LeaderboardUiState
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiBook
import com.calypsan.listenup.client.presentation.discover.RecentlyAddedUiState
import com.calypsan.listenup.web.features.discover.DiscoverPage
import com.calypsan.listenup.api.error.BookError
import com.calypsan.listenup.client.presentation.auth.LoginErrorType
import com.calypsan.listenup.client.presentation.auth.LoginUiState
import com.calypsan.listenup.client.presentation.auth.PendingApprovalUiState
import com.calypsan.listenup.client.presentation.auth.RegisterUiState
import com.calypsan.listenup.client.presentation.auth.SetupUiState
import com.calypsan.listenup.client.presentation.bookdetail.BookDetailUiState
import com.calypsan.listenup.client.presentation.auth.ForgotPasswordUiState
import com.calypsan.listenup.web.features.auth.AuthLayout
import com.calypsan.listenup.web.features.auth.ClaimInvitePanel
import com.calypsan.listenup.web.features.auth.ForgotPasswordPanel
import com.calypsan.listenup.web.features.auth.invitePreview
import com.calypsan.listenup.client.presentation.invite.ClaimInviteUiState
import com.calypsan.listenup.web.features.auth.LoginForm
import com.calypsan.listenup.web.features.auth.PendingApprovalPanel
import com.calypsan.listenup.web.features.auth.RegisterForm
import com.calypsan.listenup.web.features.auth.SetupForm
import com.calypsan.listenup.web.features.bookdetail.readyBook
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.presentation.seriesdetail.SeriesDetailUiState
import com.calypsan.listenup.web.features.seriesdetail.SeriesDetailPage
import com.calypsan.listenup.web.features.seriesdetail.readySeries
import com.calypsan.listenup.client.presentation.notifications.NotificationsUiState
import com.calypsan.listenup.web.features.notifications.NotificationsPage
import com.calypsan.listenup.web.features.notifications.notification
import com.calypsan.listenup.client.presentation.notifications.NotificationPrefsUiState
import com.calypsan.listenup.web.features.notifications.NotificationPrefsPage
import com.calypsan.listenup.web.features.notifications.pref
import com.calypsan.listenup.client.presentation.admin.LibrarySettingsUiState
import com.calypsan.listenup.web.features.admin.LibrarySettingsPage
import com.calypsan.listenup.web.features.admin.readyLibrary
import com.calypsan.listenup.web.features.setup.LibrarySetupPage
import com.calypsan.listenup.web.features.setup.setupState
import com.calypsan.listenup.client.presentation.profile.AvatarChange
import com.calypsan.listenup.client.presentation.profile.EditProfileUiState
import com.calypsan.listenup.client.presentation.profile.UserProfileUiState
import com.calypsan.listenup.web.features.profile.EditProfilePage
import com.calypsan.listenup.web.features.profile.ProfilePage
import com.calypsan.listenup.web.features.profile.editing
import com.calypsan.listenup.web.features.profile.readyProfile
import com.calypsan.listenup.web.features.seriesdetail.seriesBook
import com.calypsan.listenup.client.playback.SleepTimerState
import com.calypsan.listenup.web.features.nowplaying.NowPlayingBook
import com.calypsan.listenup.web.features.nowplaying.NowPlayingPanel
import com.calypsan.listenup.web.features.nowplaying.PlayerLink
import com.calypsan.listenup.web.features.nowplaying.PlayerSeriesLink
import com.calypsan.listenup.web.features.nowplaying.TransportChapter
import com.calypsan.listenup.web.features.contributordetail.ContributorDetailPage
import com.calypsan.listenup.web.features.contributoredit.ContributorEditPage
import com.calypsan.listenup.web.features.contributoredit.candidate
import com.calypsan.listenup.web.features.contributoredit.editingContributor
import com.calypsan.listenup.client.domain.chapter.ChapterAnchor
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterEditorUiState
import com.calypsan.listenup.client.presentation.chaptereditor.DriftPreview
import com.calypsan.listenup.client.presentation.chaptereditor.DriftProposal
import com.calypsan.listenup.client.presentation.chaptereditor.DriftRefusal
import com.calypsan.listenup.web.features.chaptereditor.ChapterEditorPage
import com.calypsan.listenup.web.features.chaptereditor.editingChapters
import com.calypsan.listenup.web.features.chaptereditor.threeChapters
import com.calypsan.listenup.web.features.seriesedit.SeriesEditPage
import com.calypsan.listenup.web.features.seriesedit.editingSeries
import com.calypsan.listenup.web.features.seriesedit.seriesCandidate
import com.calypsan.listenup.client.presentation.seriesedit.MAX_MERGE_CANDIDATES
import com.calypsan.listenup.client.presentation.contributoredit.MAX_MERGE_CANDIDATES as CONTRIBUTOR_MERGE_CAP
import com.calypsan.listenup.web.features.contributordetail.bookItem
import com.calypsan.listenup.web.features.contributordetail.readyContributor
import com.calypsan.listenup.web.features.contributordetail.roleSection
import com.calypsan.listenup.web.features.contributordetail.seriesWithBooks
import androidx.compose.runtime.Composable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Text
import com.calypsan.listenup.client.domain.model.ContributorRole
import com.calypsan.listenup.client.presentation.bookedit.BookEditUiState
import com.calypsan.listenup.web.features.bookedit.BookEditPage
import com.calypsan.listenup.client.presentation.home.HomeStatsUiState
import com.calypsan.listenup.client.presentation.home.HomeUiState
import com.calypsan.listenup.client.domain.GenreShare
import com.calypsan.listenup.client.domain.model.ContinueListeningItem
import com.calypsan.listenup.web.features.home.HomePage
import com.calypsan.listenup.web.features.home.continuing
import com.calypsan.listenup.web.features.home.readyHome
import com.calypsan.listenup.web.features.home.scanning
import com.calypsan.listenup.web.features.home.weekStats
import com.calypsan.listenup.web.features.contributors.ContributorsPage
import com.calypsan.listenup.web.features.contributors.contributor
import org.jetbrains.compose.web.renderComposable
import com.calypsan.listenup.web.features.bookdetail.BookDetailPage
import com.calypsan.listenup.client.presentation.library.LibraryUiState
import com.calypsan.listenup.web.features.library.LibraryPage
import com.calypsan.listenup.web.features.library.contractBook
import com.calypsan.listenup.web.features.library.contractLibrary
import com.calypsan.listenup.web.features.nowplaying.PlaybackNotice
import com.calypsan.listenup.web.features.nowplaying.TransportBar
import com.calypsan.listenup.web.features.nowplaying.TransportState
import com.calypsan.listenup.client.domain.model.SearchHitType
import com.calypsan.listenup.client.presentation.search.SearchUiState
import com.calypsan.listenup.web.features.search.CommandPalette
import com.calypsan.listenup.web.features.search.SearchPage
import com.calypsan.listenup.web.features.search.bookHit
import com.calypsan.listenup.web.features.search.contributorHit
import com.calypsan.listenup.web.features.search.searchResult
import com.calypsan.listenup.web.shell.AccountMenu
import com.calypsan.listenup.web.shell.NavEntry
import com.calypsan.listenup.web.shell.NavSection
import com.calypsan.listenup.web.shell.Shell
import org.w3c.dom.HTMLElement
import org.w3c.dom.css.CSSStyleSheet

/**
 * Guards the seam between Kotlin and `web.css`.
 *
 * The kit styles by class name, which makes a typo or an invented class invisible: the component
 * compiles, its tests pass, and it renders unstyled. That already happened once — a header span
 * was given a `.dt-head` class the sheet has never defined, and nothing caught it but eye.
 *
 * Asserting the *rendered* DOM against the *loaded* stylesheet catches both directions of that
 * mistake, and does it for every component at once rather than one assertion per class.
 */
class ClassContractTest :
    FunSpec({

        // Every class selector defined anywhere in the loaded sheets, including inside `@media`
        // and `@supports` blocks.
        //
        // Recursion is not a nicety: a grouping rule carries no `selectorText` of its own, so a
        // pass that reads only top-level rules skips its whole body. A class styled *only* under
        // a media query then looked undefined, and the page rendering it failed this contract —
        // a false alarm whose obvious "fix" is to restructure correct CSS until the test stops
        // complaining. `.scan-pulse` is such a class today; the spec below pins it.
        fun definedClasses(): Set<String> {
            val defined = mutableSetOf<String>()

            fun collect(rules: dynamic) {
                val length = rules.length as? Int ?: return
                for (j in 0 until length) {
                    val rule = rules.item(j)
                    val selector = rule?.selectorText as? String
                    if (selector != null) {
                        CLASS_SELECTOR.findAll(selector).forEach { defined += it.groupValues[1] }
                    } else {
                        // @media / @supports and friends: the selectors live one level down.
                        val nested = rule?.cssRules
                        if (nested != null) collect(nested)
                    }
                }
            }

            val sheets = document.styleSheets
            for (i in 0 until sheets.length) {
                val sheet = sheets.item(i) as? CSSStyleSheet ?: continue
                val rules = runCatching { sheet.cssRules }.getOrNull() ?: continue
                collect(rules.asDynamic())
            }
            return defined
        }

        fun classesUsedIn(content: @Composable () -> Unit): Set<String> {
            val host = document.createElement("div") as HTMLElement
            document.body!!.appendChild(host)
            renderComposable(root = host) { content() }

            // A modal <dialog> holds focus for the whole document and makes everything behind it
            // inert. This harness renders and never disposes, so an open one would leak that state
            // into every spec that ran afterwards — which it did, taking the command palette's
            // focus tests with it. Closing leaves the element and its classes exactly where they
            // are, which is all this contract reads.
            val dialogs = host.querySelectorAll("dialog")
            for (i in 0 until dialogs.length) {
                (dialogs.item(i) as? HTMLDialogElement)?.takeIf { it.open }?.close()
            }

            val used = mutableSetOf<String>()
            val all = host.querySelectorAll("*")
            for (i in 0 until all.length) {
                val element = all.item(i) as? HTMLElement ?: continue
                val list = element.classList
                for (j in 0 until list.length) {
                    list.item(j)?.let { used += it }
                }
            }
            return used
        }

        test("web.css is actually loaded, or this whole spec is vacuous") {
            // Without this, an unloaded stylesheet makes `definedClasses()` empty and every
            // assertion below would fail loudly rather than silently — but a *partially* loaded
            // sheet would not. Pin a class we know the sheet defines.
            definedClasses().contains("tblwrap") shouldBe true
        }

        test("a class defined only inside @media is still seen as defined") {
            // `.scan-pulse` exists solely inside web.css's prefers-reduced-motion block. Reading
            // top-level rules alone missed it — a grouping rule has no selectorText of its own —
            // so a page rendering such a class failed this contract even though the sheet defines
            // it perfectly well, and the tempting "fix" was to restructure correct CSS.
            definedClasses().contains("scan-pulse") shouldBe true
        }

        test("every class the kit renders is defined in the design sheet") {
            val used =
                classesUsedIn {
                    DataTable(
                        columns =
                            listOf(
                                TableColumn<String>("a", "A", mono = true) { Text(it) },
                                TableColumn<String>("b", "B") { Text(it) },
                            ),
                        rows = listOf("one", "two"),
                        selectable = true,
                        isSelected = { it == "one" },
                        isPlaying = { it == "two" },
                        sortKey = "a",
                        allState = SelectAllState.Some,
                        rowActions = listOf(WebIcon.Play, WebIcon.Pencil),
                    )
                    WebAppSurface {
                        Shell(
                            sections =
                                listOf(
                                    NavSection(listOf(NavEntry("home", "Home", WebIcon.Home))),
                                    NavSection(listOf(NavEntry("shelves", "Shelves", WebIcon.Bookmark)), label = "Yours"),
                                ),
                            active = "home",
                            footer = listOf(NavEntry("settings", "Settings", WebIcon.Cog)),
                            onToggleCollapse = {},
                        ) {}
                        Shell(
                            sections = listOf(NavSection(listOf(NavEntry("home", "Home", WebIcon.Home)))),
                            active = "home",
                            collapsed = true,
                            onToggleCollapse = {},
                        ) {}
                        BookDetailPage(
                            state =
                                readyBook(
                                    series =
                                        listOf(
                                            BookSeries(seriesId = "s1", seriesName = "The Stormlight Archive", sequence = 1.0),
                                        ),
                                ),
                            tab = "overview",
                            onSelectTab = {},
                            onOpenLibrary = {},
                            onPlay = {},
                        )
                        BookDetailPage(
                            state = readyBook(),
                            tab = "chapters",
                            onSelectTab = {},
                            onOpenLibrary = {},
                            onPlay = {},
                            selection = setOf(1, 2),
                        )
                        // The states with no book draw classes of their own, so they belong in
                        // the contract too — an invented class hides just as well in an empty
                        // state as in a full one.
                        BookDetailPage(
                            state = BookDetailUiState.Error(BookError.NotFound()),
                            tab = "overview",
                            onSelectTab = {},
                            onOpenLibrary = {},
                            onPlay = {},
                        )
                        BookDetailPage(
                            state = BookDetailUiState.Loading,
                            tab = "overview",
                            onSelectTab = {},
                            onOpenLibrary = {},
                            onPlay = {},
                        )
                        // Every Library state draws classes of its own — the grid and sort row
                        // from a loaded page, and the two empty states, which are the ones most
                        // likely to be styled by eye and never looked at again.
                        LibraryPage(
                            state = contractLibrary(books = listOf(contractBook("b1", "Dune"))),
                            onEvent = {},
                            onOpenBook = {},
                            onSelectFacet = {},
                        )
                        LibraryPage(
                            state = contractLibrary(syncing = true),
                            onEvent = {},
                            onOpenBook = {},
                            onSelectFacet = {},
                        )
                        LibraryPage(state = contractLibrary(), onEvent = {}, onOpenBook = {}, onSelectFacet = {})
                        LibraryPage(state = LibraryUiState.Loading, onEvent = {}, onOpenBook = {}, onSelectFacet = {})
                        // Every Contributors state: a populated author list, a populated narrator
                        // list (so `.contrib-role-chip.is-narrator` actually renders — an empty
                        // list here would exercise no row at all), the empty state, and the null
                        // loading state. The page joins this contract by hand — the render list
                        // below is explicit, so a page nobody adds here is a page whose invented
                        // classes nothing catches (which is exactly how `.contrib-list` shipped
                        // undefined).
                        ContributorsPage(
                            state = listOf(contributor("c1", "Andy Weir", 3)),
                            role = ContributorRole.AUTHOR,
                            onSelectFacet = {},
                            onOpenContributor = {},
                        )
                        ContributorsPage(
                            state = listOf(contributor("c2", "Santino Fontana", 2)),
                            role = ContributorRole.NARRATOR,
                            onSelectFacet = {},
                            onOpenContributor = {},
                        )
                        ContributorsPage(
                            state = emptyList(),
                            role = ContributorRole.NARRATOR,
                            onSelectFacet = {},
                            onOpenContributor = {},
                        )
                        ContributorsPage(
                            state = null,
                            role = ContributorRole.AUTHOR,
                            onSelectFacet = {},
                            onOpenContributor = {},
                        )
                        // Contributor Detail: a fully loaded page (role panels, a credited-as alias,
                        // a series panel) and every non-Ready state — each draws classes of its own,
                        // and a state nobody renders here is one whose invented classes nothing catches.
                        ContributorDetailPage(
                            state =
                                readyContributor(
                                    roleSections =
                                        listOf(
                                            roleSection(
                                                displayName = "Written By",
                                                bookCount = 58,
                                                previewBooks = listOf(bookItem("b1", "The Institute")),
                                            ),
                                        ),
                                    bookCreditedAs = mapOf("b1" to "Richard Bachman"),
                                    series = listOf(seriesWithBooks()),
                                ),
                            onOpenLibrary = {},
                            onOpenContributors = {},
                            onOpenBook = {},
                        )
                        ContributorDetailPage(
                            state = ContributorDetailUiState.Loading,
                            onOpenLibrary = {},
                            onOpenContributors = {},
                            onOpenBook = {},
                        )
                        ContributorDetailPage(
                            state = ContributorDetailUiState.Error("The server could not be reached."),
                            onOpenLibrary = {},
                            onOpenContributors = {},
                            onOpenBook = {},
                        )
                        ContributorDetailPage(
                            state = ContributorDetailUiState.NotFound,
                            onOpenLibrary = {},
                            onOpenContributors = {},
                            onOpenBook = {},
                        )
                        // Contributor Edit: the loaded form with everything on it (an error banner,
                        // a portrait, aliases), the skeleton, and each dialog — three dialogs that
                        // never appear together, so each needs its own render or its classes are
                        // invented in a file nothing renders.
                        ContributorEditPage(
                            state =
                                editingContributor(
                                    error = "That name is already taken.",
                                    aliases = listOf("Richard Bachman"),
                                    imagePath = "contributors/c-king.jpg",
                                ),
                            mergeCandidates = emptyList(),
                            onEvent = {},
                            onMergeQuery = {},
                        )
                        ContributorEditPage(
                            state = editingContributor(isLoading = true),
                            mergeCandidates = emptyList(),
                            onEvent = {},
                            onMergeQuery = {},
                        )
                        ContributorEditPage(
                            state = editingContributor(mergeDialogVisible = true, mergeQuery = "Bach"),
                            mergeCandidates = listOf(candidate()),
                            onEvent = {},
                            onMergeQuery = {},
                        )
                        ContributorEditPage(
                            state = editingContributor(renameCollisionCandidate = candidate()),
                            mergeCandidates = emptyList(),
                            onEvent = {},
                            onMergeQuery = {},
                        )
                        // Chapter Editor: the list with a locked row, an unsaved draft, a
                        // changed-elsewhere banner and a refused save; the drift panel in each of
                        // its three shapes (nothing pinned, ready, refused); and the two states
                        // with no list — the empty book and the skeleton.
                        ChapterEditorPage(
                            state =
                                editingChapters(
                                    selectedChapterId = "c2",
                                    isDirty = true,
                                    canUndo = true,
                                    changedElsewhere = true,
                                    lockedChapterIds = setOf("c1"),
                                ),
                            playheadMs = 61_500L,
                            onSelect = {},
                            onNudge = { _, _ -> },
                            onSnapToPlayhead = { _, _ -> },
                            onRetitle = { _, _ -> },
                            onRemove = {},
                            onAddAt = { _, _ -> },
                            onToggleLock = {},
                            onBeginDrift = {},
                            onPinAnchor = { _, _ -> },
                            onApplyDrift = {},
                            onCancelDrift = {},
                            onUndo = {},
                            onSave = {},
                            onLeave = {},
                            problem = "Chapter 2 needs a title.",
                        )
                        ChapterEditorPage(
                            state =
                                editingChapters(
                                    selectedChapterId = "c1",
                                    drift =
                                        ChapterEditorUiState.DriftState(
                                            proposal = DriftProposal(first = ChapterAnchor("c1", 0L)),
                                            preview =
                                                DriftPreview.Ready(
                                                    corrected = threeChapters(),
                                                    affectedCount = 3,
                                                    firstOffsetMs = 0L,
                                                    lastOffsetMs = 1_000L,
                                                ),
                                        ),
                                ),
                            playheadMs = 1_000L,
                            onSelect = {},
                            onNudge = { _, _ -> },
                            onSnapToPlayhead = { _, _ -> },
                            onRetitle = { _, _ -> },
                            onRemove = {},
                            onAddAt = { _, _ -> },
                            onToggleLock = {},
                            onBeginDrift = {},
                            onPinAnchor = { _, _ -> },
                            onApplyDrift = {},
                            onCancelDrift = {},
                            onUndo = {},
                            onSave = {},
                            onLeave = {},
                        )
                        ChapterEditorPage(
                            state =
                                editingChapters(
                                    drift =
                                        ChapterEditorUiState.DriftState(
                                            proposal = DriftProposal(first = ChapterAnchor("c1", 0L)),
                                            preview = DriftPreview.Refused(DriftRefusal.InvertedAnchors),
                                        ),
                                ),
                            playheadMs = null,
                            onSelect = {},
                            onNudge = { _, _ -> },
                            onSnapToPlayhead = { _, _ -> },
                            onRetitle = { _, _ -> },
                            onRemove = {},
                            onAddAt = { _, _ -> },
                            onToggleLock = {},
                            onBeginDrift = {},
                            onPinAnchor = { _, _ -> },
                            onApplyDrift = {},
                            onCancelDrift = {},
                            onUndo = {},
                            onSave = {},
                            onLeave = {},
                        )
                        ChapterEditorPage(
                            state = editingChapters(chapters = emptyList()),
                            playheadMs = 1_000L,
                            onSelect = {},
                            onNudge = { _, _ -> },
                            onSnapToPlayhead = { _, _ -> },
                            onRetitle = { _, _ -> },
                            onRemove = {},
                            onAddAt = { _, _ -> },
                            onToggleLock = {},
                            onBeginDrift = {},
                            onPinAnchor = { _, _ -> },
                            onApplyDrift = {},
                            onCancelDrift = {},
                            onUndo = {},
                            onSave = {},
                            onLeave = {},
                        )
                        ChapterEditorPage(
                            state = ChapterEditorUiState.Loading,
                            playheadMs = null,
                            onSelect = {},
                            onNudge = { _, _ -> },
                            onSnapToPlayhead = { _, _ -> },
                            onRetitle = { _, _ -> },
                            onRemove = {},
                            onAddAt = { _, _ -> },
                            onToggleLock = {},
                            onBeginDrift = {},
                            onPinAnchor = { _, _ -> },
                            onApplyDrift = {},
                            onCancelDrift = {},
                            onUndo = {},
                            onSave = {},
                            onLeave = {},
                        )
                        // Series Edit: the loaded form with an error banner and its own artwork,
                        // the same form with a staged pick (which swaps the art for a preview and
                        // adds the discard control), the skeleton, and the picker in each of its
                        // three shapes — nothing matched, a selectable list, and a capped one.
                        SeriesEditPage(
                            state = editingSeries(error = "That name is taken.", coverPath = "series/s.jpg"),
                            mergeCandidates = emptyList(),
                            onEvent = {},
                            onMergeQuery = {},
                        )
                        SeriesEditPage(
                            state = stagedCoverSeries,
                            mergeCandidates = emptyList(),
                            onEvent = {},
                            onMergeQuery = {},
                        )
                        SeriesEditPage(
                            state = editingSeries(isLoading = true),
                            mergeCandidates = emptyList(),
                            onEvent = {},
                            onMergeQuery = {},
                        )
                        SeriesEditPage(
                            state = editingSeries(mergeDialogVisible = true, mergeQuery = "Nothing"),
                            mergeCandidates = emptyList(),
                            onEvent = {},
                            onMergeQuery = {},
                        )
                        SeriesEditPage(
                            state = editingSeries(mergeDialogVisible = true, mergeQuery = "Mist"),
                            mergeCandidates =
                                (1..MAX_MERGE_CANDIDATES).map {
                                    seriesCandidate(id = "s$it", displayName = "Series $it")
                                },
                            onEvent = {},
                            onMergeQuery = {},
                        )
                        // And the contributor picker with a full page, for the truncation notice.
                        ContributorEditPage(
                            state = editingContributor(mergeDialogVisible = true, mergeQuery = "B"),
                            mergeCandidates =
                                (1..CONTRIBUTOR_MERGE_CAP).map {
                                    candidate(id = "c$it", displayName = "Person $it")
                                },
                            onEvent = {},
                            onMergeQuery = {},
                        )
                        // Series Detail: a fully loaded page — hero stats, the reading order with a
                        // progress bar and a finished mark, and the About panel — plus the two
                        // states with no series, which draw classes of their own.
                        SeriesDetailPage(
                            state =
                                readySeries(
                                    seriesDescription = "Ten orders of Knights Radiant.",
                                    books =
                                        listOf(
                                            seriesBook("b1", "The Way of Kings", 1.0),
                                            seriesBook("b2", "Words of Radiance", 2.0),
                                        ),
                                    bookProgress = mapOf(BookId("b2") to 0.4f),
                                    finishedBookIds = setOf(BookId("b1")),
                                    resumeTarget = BookId("b2"),
                                ),
                            onOpenLibrary = {},
                            onOpenBook = {},
                        )
                        SeriesDetailPage(
                            state = SeriesDetailUiState.Error("Series not found"),
                            onOpenLibrary = {},
                            onOpenBook = {},
                        )
                        SeriesDetailPage(
                            state = SeriesDetailUiState.Loading,
                            onOpenLibrary = {},
                            onOpenBook = {},
                        )
                        notificationShapes().forEach { it() }
                        notificationPrefShapes().forEach { it() }
                        librarySetupShapes().forEach { it() }
                        librarySettingsShapes().forEach { it() }
                        inboxShapes().forEach { it() }
                        serverSettingsShapes().forEach { it() }
                        categoryShapes().forEach { it() }
                        collectionShapes().forEach { it() }
                        backupShapes().forEach { it() }
                        importShapes().forEach { it() }
                        profileShapes().forEach { it() }
                        editProfileShapes().forEach { it() }
                        // Every SearchUiState variant: Idle, TooShort, Searching, Error, a
                        // zero-hit Results and a populated one. The page joins this contract by
                        // hand, same as ContributorsPage above — a state nobody adds here is a
                        // state whose invented classes nothing catches.
                        SearchPage(
                            state = SearchUiState.Idle(),
                            onQueryChanged = {},
                            onToggleType = {},
                            onOpenHit = {},
                            onRetry = {},
                            openableTypes = SearchHitType.entries.toSet(),
                        )
                        SearchPage(
                            state = SearchUiState.TooShort(query = "du", selectedTypes = emptySet()),
                            onQueryChanged = {},
                            onToggleType = {},
                            onOpenHit = {},
                            onRetry = {},
                            openableTypes = SearchHitType.entries.toSet(),
                        )
                        SearchPage(
                            state = SearchUiState.Searching(query = "dun", selectedTypes = emptySet()),
                            onQueryChanged = {},
                            onToggleType = {},
                            onOpenHit = {},
                            onRetry = {},
                            openableTypes = SearchHitType.entries.toSet(),
                        )
                        SearchPage(
                            state = SearchUiState.Error(query = "dune", selectedTypes = emptySet(), message = "oops"),
                            onQueryChanged = {},
                            onToggleType = {},
                            onOpenHit = {},
                            onRetry = {},
                            openableTypes = SearchHitType.entries.toSet(),
                        )
                        SearchPage(
                            state =
                                SearchUiState.Results(
                                    query = "zzzzz",
                                    selectedTypes = emptySet(),
                                    result = searchResult(query = "zzzzz", hits = emptyList()),
                                ),
                            onQueryChanged = {},
                            onToggleType = {},
                            onOpenHit = {},
                            onRetry = {},
                            openableTypes = SearchHitType.entries.toSet(),
                        )
                        // A book hit (openable — real chevron and button semantics) alongside a
                        // contributor hit (not in openableTypes — exercises `.is-static`, the
                        // class a hit type with no destination renders instead of a dead click).
                        SearchPage(
                            state =
                                SearchUiState.Results(
                                    query = "dune",
                                    selectedTypes = emptySet(),
                                    result =
                                        searchResult(
                                            query = "dune",
                                            hits =
                                                listOf(
                                                    bookHit("b1", "Dune", author = "Frank Herbert"),
                                                    contributorHit("c1", "Frank Herbert"),
                                                ),
                                            isOfflineResult = true,
                                        ),
                                ),
                            onQueryChanged = {},
                            onToggleType = {},
                            onOpenHit = {},
                            onRetry = {},
                            openableTypes = setOf(SearchHitType.BOOK),
                        )
                        // The command palette's own compact render of the same five
                        // SearchUiState cases, plus the highlighted-row state SearchPage never
                        // sets — every one of them joins this contract by hand, same as
                        // SearchPage's block above.
                        CommandPalette(
                            state = SearchUiState.Idle(),
                            onQueryChanged = {},
                            onOpenHit = {},
                            openableTypes = SearchHitType.entries.toSet(),
                            highlighted = null,
                        )
                        CommandPalette(
                            state = SearchUiState.TooShort(query = "du", selectedTypes = emptySet()),
                            onQueryChanged = {},
                            onOpenHit = {},
                            openableTypes = SearchHitType.entries.toSet(),
                            highlighted = null,
                        )
                        CommandPalette(
                            state = SearchUiState.Searching(query = "dun", selectedTypes = emptySet()),
                            onQueryChanged = {},
                            onOpenHit = {},
                            openableTypes = SearchHitType.entries.toSet(),
                            highlighted = null,
                        )
                        CommandPalette(
                            state = SearchUiState.Error(query = "dune", selectedTypes = emptySet(), message = "oops"),
                            onQueryChanged = {},
                            onOpenHit = {},
                            openableTypes = SearchHitType.entries.toSet(),
                            highlighted = null,
                        )
                        CommandPalette(
                            state =
                                SearchUiState.Results(
                                    query = "zzzzz",
                                    selectedTypes = emptySet(),
                                    result = searchResult(query = "zzzzz", hits = emptyList()),
                                ),
                            onQueryChanged = {},
                            onOpenHit = {},
                            openableTypes = SearchHitType.entries.toSet(),
                            highlighted = null,
                        )
                        run {
                            val hit = bookHit("b1", "Dune", author = "Frank Herbert")
                            CommandPalette(
                                state =
                                    SearchUiState.Results(
                                        query = "dune",
                                        selectedTypes = emptySet(),
                                        result =
                                            searchResult(
                                                query = "dune",
                                                hits = listOf(hit, contributorHit("c1", "Frank Herbert")),
                                            ),
                                    ),
                                onQueryChanged = {},
                                onOpenHit = {},
                                openableTypes = setOf(SearchHitType.BOOK),
                                highlighted = hit,
                            )
                        }
                        // Book Edit was absent from this contract, which is how its form wrapper
                        // shipped a class the stylesheet had never heard of. The loaded page
                        // exercises every field primitive it owns.
                        BookEditPage(
                            state =
                                BookEditUiState(
                                    isLoading = false,
                                    bookId = "b1",
                                    title = "The Institute",
                                    publisher = "Hodder",
                                    language = "en",
                                ),
                            onEvent = {},
                            onOpenLibrary = {},
                            onOpenBook = {},
                        )
                        // Home, in every shape that renders a class of its own: loading, error,
                        // and the loaded page across a live scan, a bare sync, an empty shelf and
                        // all four stats states. It joins this contract by hand like the pages
                        // above — a state nobody lists here is a state whose classes go unchecked.
                        HomePage(HomeUiState.Loading, HomeStatsUiState.Loading, {}, {}, {}, {}, {})
                        HomePage(HomeUiState.Error("nope"), HomeStatsUiState.Loading, {}, {}, {}, {}, {})
                        HomePage(readyHome(), HomeStatsUiState.Loading, {}, {}, {}, {}, {})
                        HomePage(readyHome(), HomeStatsUiState.Empty, {}, {}, {}, {}, {})
                        HomePage(readyHome(), HomeStatsUiState.Error(isRetryable = true), {}, {}, {}, {}, {})
                        HomePage(
                            readyHome(continueListening = listOf(continuing("b1", "The Institute"))),
                            weekStats(topGenres = listOf(GenreShare("Fiction", 3), GenreShare("Sci-Fi", 1))),
                            {},
                            {},
                            {},
                            {},
                            {},
                        )
                        // A slot whose book has not synced yet — the skeleton card's own classes.
                        HomePage(
                            readyHome(continueListening = listOf(ContinueListeningItem.Loading("b2"))),
                            weekStats(),
                            {},
                            {},
                            {},
                            {},
                            {},
                        )
                        HomePage(readyHome(isBuildingInitialLibrary = true), weekStats(), {}, {}, {}, {}, {})
                        HomePage(readyHome(scanProgress = scanning()), weekStats(), {}, {}, {}, {}, {})
                        // With shelves, so the row's own classes are checked, and without,
                        // so its empty state's are.
                        HomePage(readyHome(myShelves = listOf(shelf("Finished"))), weekStats(), {}, {}, {}, {}, {})
                        // Discover joins by hand for the same reason Home does. Every section is
                        // listed in all four of its shapes, because a state nobody renders here is
                        // a state whose classes nothing checks — and this page is mostly states.
                        discoverShapes().forEach { it() }
                        shelfShapes().forEach { it() }
                        devicesShapes().forEach { it() }
                        adminShapes().forEach { it() }
                        // Loading and loaded: the skeleton's class lives only in the former.
                        SettingsPage(SettingsUiState(isLoading = true), {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
                        SettingsPage(
                            SettingsUiState(
                                isLoading = false,
                                serverUrl = "https://listenup.example",
                                serverVersion = "0.9.1",
                            ),
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                        )
                        BulkBar(count = 2, actions = listOf(BulkAction("Merge", WebIcon.Merge) {}), onClear = {})
                        Panel(title = "Details", trailing = { Text("x") }) {
                            MetaList(listOf(MetaEntry("Duration", "18:40:11", machine = true)))
                        }
                        Breadcrumb(listOf("Library", "The Institute"))
                        Tabs(listOf(TabItem("a", "A", count = "3")), active = "a")
                        SegmentedControl(listOf(SegmentItem("s", "S")), active = "s")
                        Pill("Horror", selected = true, onRemove = {})
                        Cover(title = "The Institute")
                        ProgressLine(percent = 49, remaining = "9h 18m left")
                        Field(label = "Email", value = "", leading = WebIcon.Mail, onInput = {})
                        Field(label = "Email", value = "", error = true, onInput = {})
                        PasswordField(label = "Password", value = "", onInput = {})
                        AuthLayout(title = "Sign in", subtitle = "Sub", badge = "Server administrator") {
                            LoginForm(
                                state = LoginUiState.Error(LoginErrorType.InvalidCredentials),
                                openRegistration = true,
                                onSubmit = { _, _ -> },
                                onRegister = {},
                                onForgotPassword = {},
                                onClaimInvite = {},
                            )
                        }
                        AuthLayout(title = "Create admin account") {
                            SetupForm(state = SetupUiState.Idle, onSubmit = { _, _, _, _, _ -> })
                        }
                        AuthLayout(title = "Create account") {
                            RegisterForm(
                                state = RegisterUiState.Error("nope"),
                                onSubmit = { _, _, _, _ -> },
                                onBack = {},
                            )
                        }
                        // Each pending state draws different classes, so each belongs in the
                        // contract — an invented class hides just as well in a denied state.
                        AuthLayout(title = "Waiting for approval") {
                            PendingApprovalPanel(
                                state = PendingApprovalUiState.Waiting,
                                email = "ada@example.com",
                                onCheckStatus = {},
                                onCancel = {},
                                onAcknowledge = {},
                            )
                        }
                        AuthLayout(title = "Waiting for approval") {
                            PendingApprovalPanel(
                                state = PendingApprovalUiState.Approved,
                                email = "ada@example.com",
                                onCheckStatus = {},
                                onCancel = {},
                                onAcknowledge = {},
                            )
                        }
                        AuthLayout(title = "Waiting for approval") {
                            PendingApprovalPanel(
                                state = PendingApprovalUiState.Denied("no"),
                                email = "ada@example.com",
                                onCheckStatus = {},
                                onCancel = {},
                                onAcknowledge = {},
                            )
                        }
                        // Every step of the reset flow draws a different tree, so each belongs
                        // in the contract for the same reason the pending states above do.
                        listOf(
                            ForgotPasswordUiState.EnterEmail,
                            ForgotPasswordUiState.AwaitingApproval("t1"),
                            ForgotPasswordUiState.EnterCode("t1", attemptsRemaining = 2, error = "That code is wrong."),
                            ForgotPasswordUiState.Denied,
                            ForgotPasswordUiState.Complete,
                            ForgotPasswordUiState.Error("Your reset request expired. Please start again."),
                        ).forEach { resetState ->
                            AuthLayout(title = "Reset your password") {
                                ForgotPasswordPanel(
                                    state = resetState,
                                    onRequestReset = {},
                                    onCompleteReset = { _, _ -> },
                                    onCheckStatus = {},
                                    onRetryRequest = {},
                                    onBackToSignIn = {},
                                )
                            }
                        }
                        listOf(
                            ClaimInviteUiState.Idle,
                            ClaimInviteUiState.Preview(invitePreview()),
                            ClaimInviteUiState.Preview(invitePreview(valid = false)),
                            ClaimInviteUiState.Submitting,
                            ClaimInviteUiState.Claimed,
                            ClaimInviteUiState.Error("That code does not exist."),
                        ).forEach { inviteState ->
                            AuthLayout(title = "Join a library") {
                                ClaimInvitePanel(
                                    state = inviteState,
                                    onCodeEntered = {},
                                    onClaim = { _, _, _ -> },
                                    onBackToSignIn = {},
                                )
                            }
                        }
                        ToastHost(
                            ToastQueue().apply {
                                show("Saved.", ToastTone.Notice)
                                show("Could not reach the server.", ToastTone.Failure)
                            },
                        )
                        AccountMenu(onSignOut = {})
                        PlaybackNotice(message = "Couldn't start this book.", onDismiss = {})
                        playerShapes().forEach { it() }
                    }
                }

            val undefined = used - definedClasses()
            undefined shouldBe emptySet()
        }
    })

private val CLASS_SELECTOR = Regex("\\.([A-Za-z][A-Za-z0-9_-]*)")

/**
 * The preference rows in every state, including the two only a particular server produces: a
 * push-ineligible type (whose switch is disabled) and an all-unknown list (whose empty copy is the
 * only thing that renders).
 */
private fun notificationPrefShapes(): List<@Composable () -> Unit> =
    listOf(
        {
            NotificationPrefsPage(
                state =
                    NotificationPrefsUiState.Data(
                        listOf(
                            pref(type = "campfire_invite"),
                            pref(type = "registration_decision", pushEligible = false),
                        ),
                    ),
                onSetPreference = { _, _ -> },
                onRetry = {},
                onOpenSettings = {},
            )
        },
        {
            NotificationPrefsPage(
                state = NotificationPrefsUiState.Data(listOf(pref(type = "some_future_type"))),
                onSetPreference = { _, _ -> },
                onRetry = {},
                onOpenSettings = {},
            )
        },
        {
            NotificationPrefsPage(
                state = NotificationPrefsUiState.Error(InternalError()),
                onSetPreference = { _, _ -> },
                onRetry = {},
                onOpenSettings = {},
            )
        },
        {
            NotificationPrefsPage(
                state = NotificationPrefsUiState.Loading,
                onSetPreference = { _, _ -> },
                onRetry = {},
                onOpenSettings = {},
            )
        },
    )

/**
 * The folder picker in every state: a browsable list with one folder chosen, an error, an empty
 * folder, and the two loading states.
 */
private fun librarySetupShapes(): List<@Composable () -> Unit> =
    listOf(
        {
            LibrarySetupPage(
                state = setupState(selectedPaths = setOf("/srv/Audiobooks"), error = "That folder could not be read."),
                onOpenFolder = {},
                onNavigateUp = {},
                onToggleFolder = {},
                onComplete = {},
                onDismissError = {},
            )
        },
        {
            LibrarySetupPage(
                state = setupState(directories = emptyList()),
                onOpenFolder = {},
                onNavigateUp = {},
                onToggleFolder = {},
                onComplete = {},
                onDismissError = {},
            )
        },
        {
            LibrarySetupPage(
                state = setupState(isLoadingDirectories = true, directories = emptyList()),
                onOpenFolder = {},
                onNavigateUp = {},
                onToggleFolder = {},
                onComplete = {},
                onDismissError = {},
            )
        },
    )

/**
 * Library folders in every shape: the list carrying a transient error and the post-add notice, a
 * library watching nothing, the browser populated / loading / empty, and the page's own two
 * non-Ready states.
 */
private fun librarySettingsShapes(): List<@Composable () -> Unit> {
    fun page(
        state: LibrarySettingsUiState,
        scanStarted: Boolean = false,
    ): @Composable () -> Unit =
        {
            LibrarySettingsPage(state, scanStarted, {}, {}, {}, {}, {}, {}, {}, {})
        }

    return listOf(
        // A folder list wearing both the dismissible error and the "scanning it now" notice.
        page(
            readyLibrary(error = InternalError(debugInfo = "boom")),
            scanStarted = true,
        ),
        // Every folder removed. Reachable, and the only place .lset-empty renders in list mode.
        page(readyLibrary(folders = emptyList())),
        page(readyLibrary(showFolderBrowser = true)),
        page(readyLibrary(showFolderBrowser = true, isBrowserLoading = true)),
        page(readyLibrary(showFolderBrowser = true, browserEntries = emptyList())),
        page(LibrarySettingsUiState.Error(InternalError(debugInfo = "boom"))),
        page(LibrarySettingsUiState.Loading),
    )
}

/**
 * A listener's page: a full profile, an empty one (whose "nothing yet" copy is the only thing that
 * renders), and the two states with no profile at all.
 */
private fun inboxShapes(): List<@Composable () -> Unit> {
    fun page(state: AdminInboxUiState): @Composable () -> Unit =
        {
            AdminInboxPage(state, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }

    return listOf(
        // Both halves populated, a row selected (so the bulk bar and the tick render), and both
        // notices up. Selection is what draws `.is-sel` and `.bulk` — a contract that only listed
        // the resting state would leave every one of those unchecked.
        page(
            readyInbox(
                books = listOf(inboxBook(id = "b1"), inboxBook(id = "b2", author = null)),
                selectedBookIds = setOf("b1"),
                lastReleasedCount = 2,
                error = "No library available",
                scanIssues = listOf(scanIssue(), scanIssue(id = "i2", detail = "ffprobe: EBML")),
            ),
        ),
        // Issues with no books, and books with no issues — each half renders alone.
        page(readyInbox(books = emptyList(), scanIssues = listOf(scanIssue()))),
        page(readyInbox()),
        // Both empty, which is the only shape that draws the empty block.
        page(readyInbox(books = emptyList(), scanIssues = emptyList())),
        page(AdminInboxUiState.Error("Server said no.")),
        page(AdminInboxUiState.Loading),
    )
}

private fun serverSettingsShapes(): List<@Composable () -> Unit> {
    fun page(state: AdminSettingsUiState): @Composable () -> Unit =
        {
            ServerSettingsPage(state, {}, {}, {}, {}, {}, {}, {}, {})
        }

    return listOf(
        // Dirty and wearing a failed write — `.srv-err` renders nowhere else.
        page(readyServerSettings(isDirty = true, error = InternalError(debugInfo = "boom"))),
        page(readyServerSettings(isSaving = true, isDirty = true)),
        page(AdminSettingsUiState.Error(InternalError(debugInfo = "boom"))),
        page(AdminSettingsUiState.Loading),
    )
}

private fun categoryShapes(): List<@Composable () -> Unit> {
    fun page(state: AdminCategoriesUiState): @Composable () -> Unit =
        {
            CategoriesPage(state, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, { _, _ -> }, { _, _ -> }, {}, {})
        }

    val child = genre(id = "g2", name = "Fantasy", path = "/fiction/fantasy", bookCount = 3)
    val parent = genre(id = "g1", name = "Fiction", path = "/fiction", bookCount = 10)
    // Expanded, so both a parent row (with a twisty) and a leaf row (with the gap that stands in
    // for one) render — they draw different classes and only an expanded tree has both.
    val tree = listOf(node(parent, children = listOf(node(child, depth = 1))))

    return listOf(
        page(readyCategories(tree = tree, expandedIds = setOf("g1"), totalBookCount = 13)),
        page(readyCategories(tree = tree, error = InternalError(debugInfo = "boom"))),
        // The only shape that draws the empty block.
        page(readyCategories(tree = emptyList(), genres = emptyList())),
        page(AdminCategoriesUiState.Error(InternalError(debugInfo = "boom"))),
        page(AdminCategoriesUiState.Loading),
    )
}

private fun bookHit() =
    com.calypsan.listenup.client.domain.model.SearchHit(
        id = "b9",
        type = com.calypsan.listenup.client.domain.model.SearchHitType.BOOK,
        name = "The Way of Kings",
    )

private fun collectionShapes(): List<@Composable () -> Unit> {
    fun list(state: AdminCollectionsUiState): @Composable () -> Unit = { CollectionsPage(state, {}, {}, {}, {}, {}) }

    fun detail(state: AdminCollectionDetailUiState): @Composable () -> Unit =
        {
            CollectionDetailPage(state, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }

    return listOf(
        // An ordinary collection and a managed one side by side — the padlock renders only on the
        // second, and Delete only on the first.
        list(
            readyCollections(
                collections = listOf(collection(), collection(id = "c2", name = "All books", isSystem = true)),
                error = "No library available",
            ),
        ),
        list(readyCollections(collections = emptyList())),
        list(AdminCollectionsUiState.Error("nope")),
        list(AdminCollectionsUiState.Loading),
        // Dirty, so the Save row renders; erroring, so the notice does.
        detail(readyDetail(editedName = "Changed", error = "nope", shares = listOf(shareFixture()))),
        // Both panels open — each draws classes that render nowhere else.
        detail(readyDetail(showAddBooks = true, bookQuery = "kings", bookResults = listOf(bookHit()))),
        detail(readyDetail(showAddMemberSheet = true, availableUsers = listOf(personFixture()))),
        detail(readyDetail(isSystem = true, books = emptyList())),
        detail(AdminCollectionDetailUiState.Error("nope")),
        detail(AdminCollectionDetailUiState.Loading),
    )
}

private fun backupShapes(): List<@Composable () -> Unit> {
    fun list(
        state: AdminBackupUiState,
        uploadState: RestoreFromFileUiState = RestoreFromFileUiState.Idle,
    ): @Composable () -> Unit =
        {
            BackupsPage(state, uploadState, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }

    fun restore(
        state: RestoreBackupUiState,
        progress: BackupEvent? = null,
    ): @Composable () -> Unit = { RestorePage(state, progress, {}, {}, {}, {}) }

    return listOf(
        // A list wearing its error, with a row to draw the row classes.
        list(readyBackups(error = InternalError(debugInfo = "boom"))),
        list(readyBackups(backups = emptyList())),
        list(AdminBackupUiState.Error(InternalError(debugInfo = "boom"))),
        list(AdminBackupUiState.Loading),
        // Idle wearing a prior failure — `.rst-err` renders nowhere else.
        restore(RestoreBackupUiState.Idle(error = InternalError(debugInfo = "boom"))),
        restore(RestoreBackupUiState.Confirming),
        restore(RestoreBackupUiState.Restoring, progress = BackupEvent.Swapping),
        // Migrated, so `.rst-schema` renders.
        restore(RestoreBackupUiState.Completed(restoreResult(from = "6", to = "7"))),
    )
}

private fun importShapes(): List<@Composable () -> Unit> {
    fun list(state: ABSImportListUiState): @Composable () -> Unit = { ImportsPage(state, {}, {}, {}, {}, {}) }

    fun flow(state: ImportFlowUiState): @Composable () -> Unit =
        {
            ImportFlowPage(state, {}, { _, _ -> }, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})
        }

    return listOf(
        list(readyImports(error = InternalError(debugInfo = "boom"))),
        list(readyImports(imports = emptyList())),
        list(ABSImportListUiState.Error(InternalError(debugInfo = "boom"))),
        list(ABSImportListUiState.Loading),
        flow(ImportFlowUiState.Idle),
        flow(ImportFlowUiState.Analyzing(3, 10, "Elantris", 1, 4)),
        // Review with a book needing a decision AND the search panel open — several classes
        // render only in one of those two.
        flow(
            review(
                ambiguous = listOf(absItem()),
                bookSearch =
                    BookSearchState(
                        com.calypsan.listenup.core
                            .AbsItemId("ai1"),
                        query = "elantris",
                        results =
                            listOf(
                                com.calypsan.listenup.client.presentation.admin.imports.BookSearchHit(
                                    com.calypsan.listenup.core
                                        .BookId("b1"),
                                    "Elantris",
                                    "Brandon Sanderson",
                                ),
                            ),
                        isSearching = false,
                    ),
            ),
        ),
        // Done carrying books it could not place — `.iflow-note` renders nowhere else.
        flow(ImportFlowUiState.Done(importResult(booksNotInLibrary = 3))),
        flow(ImportFlowUiState.Error(InternalError(debugInfo = "boom"))),
    )
}

private fun profileShapes(): List<@Composable () -> Unit> =
    listOf(
        { ProfilePage(readyProfile(), onOpenBook = {}, onOpenShelf = {}, onRetry = {}, onEditProfile = {}) },
        // Own profile, for `.prof-edit` — it renders nowhere else.
        { ProfilePage(readyProfile(isOwnProfile = true), {}, {}, {}, {}) },
        {
            ProfilePage(
                state = readyProfile(recentBooks = emptyList(), publicShelves = emptyList()),
                onOpenBook = {},
                onOpenShelf = {},
                onRetry = {},
                onEditProfile = {},
            )
        },
        { ProfilePage(UserProfileUiState.Error("No such listener."), {}, {}, {}, {}) },
        { ProfilePage(UserProfileUiState.Loading, {}, {}, {}, {}) },
    )

/**
 * Edit Profile in every shape that draws a class of its own.
 *
 * The photo row alone has three: the saved avatar, a staged upload previewing from its own bytes,
 * and a staged removal previewing as the monogram. A contract that only rendered the happy path
 * would leave two of the three unchecked, and they are exactly the ones a reader sees mid-edit.
 */
private fun editProfileShapes(): List<@Composable () -> Unit> {
    fun form(
        state: EditProfileUiState,
        saveError: String? = null,
    ): @Composable () -> Unit =
        {
            EditProfilePage(state, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, saveError)
        }

    return listOf(
        // A dirty form with a photo to remove and a failed save on it — most of the classes.
        form(editing(tagline = "Counting on it.", hasImageAvatar = true, isDirty = true), saveError = "Nope."),
        form(editing(avatarChange = AvatarChange.Upload(byteArrayOf(1), "image/png"), hasImageAvatar = true)),
        form(editing(avatarChange = AvatarChange.RevertToAuto, hasImageAvatar = true)),
        form(EditProfileUiState.Error("No user data available")),
        form(EditProfileUiState.Loading),
    )
}

/**
 * The notification inbox in every state it has, plus the sidebar wearing a badge.
 *
 * Lifted out of the test body for the same reason [discoverShapes] was: this one contract renders
 * every page in the app, and the class had grown past the size the build allows. Grouping by
 * surface keeps each block readable and the class inside its budget.
 */
private fun notificationShapes(): List<@Composable () -> Unit> =
    listOf(
        {
            // The inbox in all three states, plus a read row — the dot's "read"
            // form is a class of its own and would otherwise never be rendered here.
            NotificationsPage(
                state =
                    NotificationsUiState.Data(
                        listOf(
                            notification(id = "n1"),
                            notification(id = "n2", readAt = 1L),
                        ),
                    ),
                nowMs = 1_800_000_000_000L,
                onOpen = {},
            )
            NotificationsPage(state = NotificationsUiState.Empty, nowMs = 0L, onOpen = {})
            NotificationsPage(state = NotificationsUiState.Loading, nowMs = 0L, onOpen = {})
            // The sidebar with a badge on it. `Shell` above renders without one, so
            // `.nav-badge` joins the contract from here.
            Shell(
                sections = listOf(NavSection(listOf(NavEntry("home", "Home", WebIcon.Home)))),
                active = "home",
                footer = listOf(NavEntry("notifications", "Notifications", WebIcon.Bell, badge = 3)),
                onToggleCollapse = {},
            ) {}
        },
    )

/**
 * The docked transport bar and the expanded player.
 *
 * The panel appears only after a gesture this harness does not make, so it is rendered open here
 * by hand — a class invented inside it would otherwise go unchecked.
 */
private fun playerShapes(): List<@Composable () -> Unit> =
    listOf(
        {
            TransportBar(
                state =
                    TransportState(
                        title = "The Institute",
                        isPlaying = true,
                        positionMs = 61_000,
                        durationMs = 3_600_000,
                    ),
                onPlayPause = {},
                onSeek = {},
                onSkipBack = {},
                onSkipForward = {},
                onSetSpeed = {},
                volumeBoostDb = 6f,
            )
            // The expanded player, rendered open. It cannot ride the bar above into
            // this contract — it appears only after a click, and this harness renders
            // without gesturing — so a class invented in there would go unchecked.
            // Both the fully-known book and the one Room has not seen: the second
            // draws a different subset (no byline, no series, no Go to book).
            NowPlayingPanel(
                open = true,
                state =
                    TransportState(
                        title = "The Way of Kings",
                        isPlaying = true,
                        positionMs = 61_000,
                        durationMs = 3_600_000,
                    ),
                book =
                    NowPlayingBook(
                        bookId = "b1",
                        coverHash = "abc",
                        authors = listOf(PlayerLink("c1", "Brandon Sanderson")),
                        narrators = "Michael Kramer",
                        series = listOf(PlayerSeriesLink("s1", "The Stormlight Archive", "1")),
                    ),
                chapters = listOf(TransportChapter("The Shattered Plains", 0L)),
                currentChapterIndex = 0,
                sleepTimer = SleepTimerState.Inactive,
                volumeBoostDb = 6f,
                onPlayPause = {},
                onSeek = {},
                onSkipBack = {},
                onSkipForward = {},
                onSeekToChapter = {},
                onOpenSpeed = {},
                onOpenChapters = {},
                onOpenBoost = {},
                onOpenSleep = {},
                onOpenBook = {},
                onOpenSeries = {},
                onOpenContributor = {},
                onDismiss = {},
            )
            NowPlayingPanel(
                open = true,
                state =
                    TransportState(
                        title = "A book the mirror has not seen",
                        isPlaying = false,
                        positionMs = 0,
                        durationMs = 3_600_000,
                    ),
                book = null,
                chapters = emptyList(),
                currentChapterIndex = null,
                sleepTimer = SleepTimerState.Inactive,
                volumeBoostDb = 0f,
                onPlayPause = {},
                onSeek = {},
                onSkipBack = {},
                onSkipForward = {},
                onSeekToChapter = {},
                onOpenSpeed = {},
                onOpenChapters = {},
                onOpenBoost = {},
                onOpenSleep = {},
                onOpenBook = {},
                onOpenSeries = {},
                onOpenContributor = {},
                onDismiss = {},
            )
        },
    )

/**
 * Every shape Discover can be in, as callable render blocks.
 *
 * A list rather than a single call because the page has five sections with four states each, and
 * only the populated ones render most of the classes — a contract that only listed the happy path
 * would leave every skeleton, empty and error class unchecked.
 */
private fun discoverShapes(): List<@Composable () -> Unit> {
    val entry =
        LeaderboardEntry(
            rank = 1,
            userId = "u1",
            displayName = "Simon",
            totalSeconds = 7_500,
            booksFinished = 3,
            currentStreakDays = 2,
            longestStreakDays = 9,
        )
    val snapshot = LeaderboardSnapshot(time = listOf(entry), books = listOf(entry), streak = listOf(entry))
    val listener =
        CurrentlyListeningUiSession(
            sessionId = "s1",
            userId = "u1",
            bookId = "b1",
            bookTitle = "The Institute",
            authorName = "Stephen King",
            coverPath = null,
            coverHash = null,
            displayName = "Simon",
            lastActiveAt = 0L,
            isLive = true,
        )
    val activity =
        ActivityUiModel(
            id = "a1",
            userId = "u1",
            type = "finished_book",
            occurredAt = 0L,
            userDisplayName = "Simon",
            bookId = "b1",
            bookTitle = "The Institute",
            bookAuthorName = "Stephen King",
            bookCoverPath = null,
            isReread = false,
            durationMs = 0L,
            milestoneValue = 0,
            milestoneUnit = null,
            shelfId = null,
            shelfName = null,
        )
    // A row with no book: the plain-text variant, which renders a different element entirely.
    val joined = activity.copy(id = "a2", type = "user_joined", bookId = null, bookTitle = null)

    fun page(
        books: DiscoverBooksUiState = DiscoverBooksUiState.Loading,
        recentlyAdded: RecentlyAddedUiState = RecentlyAddedUiState.Loading,
        currentlyListening: CurrentlyListeningUiState = CurrentlyListeningUiState.Loading,
        leaderboard: LeaderboardUiState = LeaderboardUiState.Loading,
        activityState: ActivityFeedUiState = ActivityFeedUiState.Loading,
        shelves: DiscoverShelvesUiState = DiscoverShelvesUiState.Loading,
    ): @Composable () -> Unit =
        {
            DiscoverPage(
                books = books,
                recentlyAdded = recentlyAdded,
                currentlyListening = currentlyListening,
                leaderboard = leaderboard,
                activity = activityState,
                shelves = shelves,
                nowMs = 0L,
                onOpenBook = {},
                onOpenShelf = {},
                onSelectPeriod = {},
                onSelectCategory = {},
            )
        }

    return listOf(
        // Every section in its skeleton.
        page(),
        // Every section in its error.
        page(
            books = DiscoverBooksUiState.Error("nope"),
            recentlyAdded = RecentlyAddedUiState.Error("nope"),
            currentlyListening = CurrentlyListeningUiState.Error("nope"),
            leaderboard = LeaderboardUiState.Error(isRetryable = true),
            activityState = ActivityFeedUiState.Error("nope"),
            shelves = DiscoverShelvesUiState.Error("nope"),
        ),
        // Every section empty.
        page(
            books = DiscoverBooksUiState.Ready(emptyList()),
            recentlyAdded = RecentlyAddedUiState.Ready(emptyList()),
            currentlyListening = CurrentlyListeningUiState.Ready(emptyList()),
            leaderboard = LeaderboardUiState.Empty,
            activityState = ActivityFeedUiState.Ready(emptyList()),
            shelves = DiscoverShelvesUiState.Ready(emptyList()),
        ),
        // Every section populated — where most of the classes actually live.
        page(
            books = DiscoverBooksUiState.Ready(listOf(discoverBook())),
            recentlyAdded = RecentlyAddedUiState.Ready(listOf(recentBook())),
            currentlyListening = CurrentlyListeningUiState.Ready(listOf(listener, listener.copy(sessionId = "s2", isLive = false))),
            leaderboard =
                LeaderboardUiState.Data(
                    snapshot = snapshot,
                    period = LeaderboardPeriod.Week,
                    category = LeaderboardCategory.Time,
                ),
            activityState = ActivityFeedUiState.Ready(listOf(activity, joined)),
            shelves =
                DiscoverShelvesUiState.Ready(
                    listOf(
                        DiscoverUserShelves(
                            user = DiscoverShelfOwner(id = "u1", displayName = "Ada"),
                            shelves =
                                listOf(
                                    DiscoverShelfUi(
                                        id = "s1",
                                        name = "Comfort reads",
                                        description = null,
                                        bookCount = 4,
                                        totalDurationSeconds = 0,
                                    ),
                                ),
                        ),
                    ),
                ),
        ),
    )
}

private fun discoverBook() =
    DiscoverUiBook(
        id = "b1",
        title = "The Institute",
        authorName = "Stephen King",
        coverPath = null,
        coverHash = null,
        seriesName = null,
    )

private fun recentBook() =
    RecentlyAddedUiBook(
        id = "b2",
        title = "Dune",
        authorName = "Frank Herbert",
        coverPath = null,
        coverHash = null,
        createdAt = 0L,
    )

/**
 * Every shape the two shelf screens can be in.
 *
 * The owner and non-owner variants are both listed: the grip and the remove control exist only for
 * an owner, so a contract that rendered one of them would leave the other's classes unchecked.
 */
private fun shelfShapes(): List<@Composable () -> Unit> {
    val book =
        ShelfBook(
            id = BookId("b1"),
            title = "The Institute",
            authorNames = listOf("Stephen King"),
            coverPath = null,
            coverHash = null,
        )

    fun detail(
        isOwner: Boolean,
        books: List<ShelfBook>,
        isPrivate: Boolean = false,
    ) = ShelfDetailUiState.Ready(
        detail =
            ShelfDetail(
                id = ShelfId("s1"),
                name = "Comfort reads",
                description = "Books to fall asleep to.",
                isPrivate = isPrivate,
                isOwner = isOwner,
                bookCount = books.size,
                totalDurationSeconds = 7_200,
                books = books,
            ),
        isOwner = isOwner,
    )

    fun detailPage(
        state: ShelfDetailUiState,
        notice: String? = null,
    ): @Composable () -> Unit = { ShelfDetailPage(state, notice, {}, {}, {}, {}, {}, {}) }

    fun editPage(
        state: CreateEditShelfUiState,
        isEditing: Boolean,
    ): @Composable () -> Unit = { ShelfEditPage(state, isEditing, { _, _, _ -> }, {}, {}, {}) }

    return listOf(
        detailPage(ShelfDetailUiState.Loading),
        // With a notice, so its own classes are checked.
        detailPage(ShelfDetailUiState.Loading, notice = "Could not reorder this shelf."),
        detailPage(ShelfDetailUiState.Error("nope")),
        detailPage(detail(isOwner = true, books = emptyList())),
        detailPage(detail(isOwner = true, books = listOf(book), isPrivate = true)),
        detailPage(detail(isOwner = false, books = listOf(book))),
        editPage(CreateEditShelfUiState.Idle, isEditing = false),
        editPage(CreateEditShelfUiState.LoadingExisting, isEditing = true),
        editPage(CreateEditShelfUiState.Loaded("Comfort reads", "", true), isEditing = true),
        editPage(CreateEditShelfUiState.Saving, isEditing = true),
        editPage(CreateEditShelfUiState.Error("nope"), isEditing = true),
    )
}

/**
 * Every shape the Devices screen can be in, plus the dialog it opens.
 *
 * The dialog is rendered here rather than only in its own spec because its classes belong to the
 * sheet like any other — and it is the one component that renders outside the mount's subtree, in
 * the browser's top layer, which is exactly the kind of thing a contract stops going unstyled.
 */
private fun devicesShapes(): List<@Composable () -> Unit> {
    fun device(
        id: String,
        name: String,
        isCurrent: Boolean = false,
    ) = DeviceRow(
        sessionId = id,
        displayName = name,
        secondary = "iOS 17.2 · ListenUp 1.0.0",
        lastUsedAt = 0L,
        isCurrent = isCurrent,
    )

    fun page(state: DevicesUiState): @Composable () -> Unit = { DevicesPage(state, 0L, {}, {}, {}) }

    return listOf(
        page(DevicesUiState.Loading),
        page(DevicesUiState.Error(InternalError(debugInfo = "nope"))),
        // No other devices: the empty line has its own class.
        page(DevicesUiState.Ready(listOf(device("s1", "This Mac", isCurrent = true)))),
        page(
            DevicesUiState.Ready(
                devices = listOf(device("s1", "This Mac", isCurrent = true), device("s2", "Simon's iPhone")),
                signingOut = setOf("s2"),
            ),
        ),
        {
            ConfirmDialog(
                open = true,
                title = "Sign out everywhere?",
                body = "Every device is signed out, including this one.",
                confirmLabel = "Sign out everywhere",
                onConfirm = {},
                onDismiss = {},
            )
        },
    )
}

/** Admin in its loading, populated and errored shapes — the badge and banner live only in some. */
private fun adminShapes(): List<@Composable () -> Unit> {
    fun user(
        id: String,
        name: String,
        isRoot: Boolean = false,
        status: String = "ACTIVE",
    ) = AdminUserInfo(
        id = id,
        email = "$id@example.com",
        displayName = name,
        firstName = null,
        lastName = null,
        isRoot = isRoot,
        role = "MEMBER",
        status = status,
        createdAt = "2026-01-01",
    )

    fun page(state: AdminUiState): @Composable () -> Unit = { AdminPage(state, 0L, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}) }

    return listOf(
        page(AdminUiState.Loading),
        page(AdminUiState.Ready(error = "nope")),
        page(
            AdminUiState.Ready(
                users = listOf(user("u1", "Simon", isRoot = true), user("u2", "Ada")),
                pendingUsers = listOf(user("u3", "Grace", status = "PENDING_APPROVAL")),
                pendingInvites =
                    listOf(
                        InviteInfo(
                            id = "i1",
                            code = "ABC123",
                            name = "Alan",
                            email = "alan@example.com",
                            role = "MEMBER",
                            expiresAt = "2026-02-01",
                            claimedAt = null,
                            url = "https://listenup.example/i/ABC123",
                            createdAt = "2026-01-01",
                        ),
                    ),
                pendingPasswordResets =
                    listOf(
                        PasswordResetRequest(
                            id = "r1",
                            userId = UserId("u2"),
                            displayName = "Ada",
                            email = "ada@example.com",
                            requestedAt = 0L,
                            expiresAt = 0L,
                        ),
                    ),
            ),
        ),
    )
}

/**
 * The staged-cover shape, hoisted so its bytes keep ONE identity for the life of this spec.
 *
 * ⛔ Not inline. `CoverPickerField` remembers its object URL keyed on the byte array, and a
 * `ByteArray` compares by identity — so a caller that builds a fresh array in the composable's
 * argument list invalidates that key on every recomposition, and the field creates and revokes a
 * blob URL every frame. Every real caller passes the array the ViewModel is holding, so this is a
 * fixture hazard rather than a product one; inline, it burned enough of the browser's event loop
 * to push `webAuthKotest`'s WebSocket handshakes past their timeout (247 RPC sockets against a
 * normal 126, two transport specs failing) with nothing in the failure pointing back here.
 */
private val stagedCoverSeries = editingSeries(pendingCoverData = byteArrayOf(1, 2, 3))
