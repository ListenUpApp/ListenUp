package com.calypsan.listenup.web.design

import com.calypsan.listenup.web.features.admin.AdminPage
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
import com.calypsan.listenup.web.features.auth.ForgotPasswordPanel
import com.calypsan.listenup.web.features.auth.LoginForm
import com.calypsan.listenup.web.features.auth.PendingApprovalPanel
import com.calypsan.listenup.web.features.auth.RegisterForm
import com.calypsan.listenup.web.features.auth.SetupForm
import com.calypsan.listenup.web.features.bookdetail.readyBook
import com.calypsan.listenup.client.presentation.contributordetail.ContributorDetailUiState
import com.calypsan.listenup.web.features.contributordetail.ContributorDetailPage
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
                            state = readyBook(),
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
                        SettingsPage(SettingsUiState(isLoading = true), {}, {}, {}, {}, {}, {}, {}, {})
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
                        AccountMenu(onSignOut = {})
                        PlaybackNotice(message = "Couldn't start this book.", onDismiss = {})
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
                            onCycleSpeed = {},
                        )
                    }
                }

            val undefined = used - definedClasses()
            undefined shouldBe emptySet()
        }
    })

private val CLASS_SELECTOR = Regex("\\.([A-Za-z][A-Za-z0-9_-]*)")

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
