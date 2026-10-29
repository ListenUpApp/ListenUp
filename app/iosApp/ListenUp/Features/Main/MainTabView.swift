import SwiftUI
import Shared

/// Root tab view for the main authenticated app experience.
///
/// Structure:
/// - Native iOS 26 `TabView` in the sidebar-adaptable style. The compact tab bar has Home, Library,
///   Discover and a search-role Search tab; the iPad sidebar lists the four Library sections as
///   their own entries and adds Downloads and Settings (HIG, Tab bars: "the tab bar's convertible
///   sidebar-style appearance can provide access to content that people use less frequently").
/// - Each tab wraps content in a `NavigationStack`; the routing rules live in `MainShellModel`.
/// - The window's tab and stacks survive in `@SceneStorage`, per window.
/// - The mini player is the tab view's bottom accessory; the full player is a `fullScreenCover`
///   that zooms out of the mini player's cover (`PlayerTransition`)
struct MainTabView: View {
    @Environment(\.dependencies) private var deps
    @Environment(DeepLinkRouter.self) private var deepLinkRouter
    @Environment(PushTapRouter.self) private var pushTapRouter
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.verticalSizeClass) private var verticalSizeClass
    @Environment(\.scenePhase) private var scenePhase
    @State private var shell = MainShellModel()
    @State private var playerObserver: PlayerObserver?
    /// One library projection for every Library tab and sidebar entry in this window.
    @State private var libraryObserver: LibraryObserver?
    /// The held-for-review count for the Library badge and the Books entry, one per window.
    @State private var inboxBadge: InboxBadgeObserver?
    /// The books an admin sees locked on every cover card, one set per window.
    @State private var restrictedBooks: RestrictedBooksObserver?
    /// Whether the tab bar lists the Library sections (sidebar shown) or collapses them into one
    /// item (sidebar hidden); reported by every tab's stack through `TabBarSectionsProbe`.
    @State private var isTabBarShowingSections = true
    @State private var bookLinkError: BookLinkError?
    /// The window's book share links, for every book context menu under the shell.
    @State private var shareLinks = BookShareLinks()
    /// Identifies this window to the process-wide `PushTapRouter`, so a tap lands in one window.
    @State private var sceneID = UUID()
    @State private var hasRestoredNavigation = false
    /// Bumped when the full player closes, so VoiceOver returns to the mini player's book.
    @State private var miniPlayerFocusRequest = 0

    /// The window's tab and stacks, restored when the system brings the window back.
    @SceneStorage("shell.navigation") private var storedNavigation: Data?
    /// The person's sidebar arrangement (HIG, Sidebars: "let people customize the contents of a
    /// sidebar"), shared by every window.
    @AppStorage("shell.sidebarCustomization") private var sidebarCustomization = TabViewCustomization()

    /// Pairs a list cell with the detail page it zooms into. One namespace for the whole shell so
    /// a hero works from any tab and any list that opens a book, contributor, or series.
    @Namespace private var heroNamespace
    /// Pairs the mini player's cover with the full player that zooms out of it.
    @Namespace private var playerNamespace

    private enum BookLinkError: Identifiable {
        case wrongServer, notConnected
        var id: Int { self == .wrongServer ? 0 : 1 }
        var message: String {
            switch self {
            case .wrongServer: String(localized: "error.book_wrong_server")
            case .notConnected: String(localized: "error.book_not_connected")
            }
        }
    }

    /// A window that is regular in both directions (an iPad full screen, the open iPhone Duo) shows
    /// the sidebar; a phone in either orientation, the closed Duo and a narrow iPad window (1/3
    /// Split View) the tab bar.
    private var usesSidebar: Bool {
        MainShellModel.usesSidebar(
            horizontalSizeClass: horizontalSizeClass,
            verticalSizeClass: verticalSizeClass
        )
    }

    /// Whether the sidebar lists its sections: the tab bar's own answer, or the sidebar-first placement's.
    private var sidebarListsSections: Bool {
        SidebarSections.areListed(
            tabBarSaysShowing: isTabBarShowingSections,
            usesSidebar: usesSidebar,
            placesSidebarFirst: SidebarFirstTabBar.isAvailable
        )
    }

    /// Books held for review — 0 for anyone who is not an admin, which hides every badge.
    private var heldCount: Int { inboxBadge?.heldCount ?? 0 }

    /// The sidebar's badges: each section's count and the Inbox's while the sections are listed, the held count on
    /// the collapsed Library item when not.
    private var libraryBadges: LibrarySidebarBadges {
        LibrarySidebarBadges.forPlacement(
            counts: libraryObserver?.sectionCounts.counts ?? .zero,
            heldCount: heldCount,
            showingSections: sidebarListsSections
        )
    }

    var body: some View {
        @Bindable var shell = shell
        TabView(selection: $shell.selectedTab) {
            SwiftUI.Tab(ShellTab.home.title, systemImage: "house.fill", value: ShellTab.home) {
                tabStack(.home) { HomeView(onSelectTab: { shell.select($0) }) }
            }
            .customizationID("listenup.home")

            libraryTabs

            SwiftUI.Tab(ShellTab.discover.title, systemImage: "sparkles", value: ShellTab.discover) {
                tabStack(.discover) {
                    DiscoverView(
                        leaderboardLandingRequest: shell.leaderboardLandingRequest,
                        onSelectTab: { shell.select($0) }
                    )
                }
            }
            .customizationID("listenup.discover")

            if usesSidebar {
                SwiftUI.Tab(ShellTab.downloads.title, systemImage: "arrow.down.circle", value: ShellTab.downloads) {
                    tabStack(.downloads) { StorageView() }
                }
                .customizationID("listenup.downloads")
                .tabPlacement(.sidebarOnly)

                SwiftUI.Tab(ShellTab.settings.title, systemImage: "gearshape", value: ShellTab.settings) {
                    tabStack(.settings) { SettingsView() }
                }
                .customizationID("listenup.settings")
                .tabPlacement(.sidebarOnly)
            }

            SwiftUI.Tab(value: ShellTab.search, role: .search) {
                tabStack(.search) { SearchView(path: pathBinding(.search), focusRequest: shell.searchFocusRequest) }
            }
            .customizationID("listenup.search")
        }
        .environment(\.bookShareLinks, shareLinks)
        .environment(\.restrictedBooks, restrictedBooks)
        .task { await shareLinks.load() }
        .tabViewStyle(.sidebarAdaptable)
        .modifier(SidebarFirstTabBar(usesSidebar: usesSidebar))
        .environment(\.sidebarListsSections, sidebarListsSections)
        .tabViewCustomization($sidebarCustomization)
        // HIG, Tab bars: an attached accessory "like the MiniPlayer in Music" moves inline with
        // the tab bar when it minimizes on scroll.
        .tabBarMinimizeBehavior(.onScrollDown)
        .modifier(MiniPlayerAccessory(isEnabled: isMiniPlayerShown) {
            if let playerObserver {
                MiniPlayerBar(
                    observer: playerObserver,
                    transitionNamespace: playerNamespace,
                    onOpen: { shell.isPlayerPresented = true },
                    focusReturnRequest: miniPlayerFocusRequest
                )
            }
        })
        // The full player is a system full-screen modal that zooms out of the mini player's cover.
        .playerPresentation(
            isPresented: $shell.isPlayerPresented, namespace: playerNamespace,
            // Focus goes back to the mini player only after a plain close; a close that navigates leaves it to the
            // destination.
            onDismiss: { if shell.takeFocusReturn() { miniPlayerFocusRequest += 1 } }
        ) {
            if let playerObserver {
                FullScreenPlayerView(
                    observer: playerObserver,
                    onViewDetails: {
                        if let bookId = playerObserver.currentBookId { shell.open(BookDestination(id: bookId)) }
                    },
                    onViewSeries: { seriesId in shell.open(SeriesDestination(id: seriesId)) },
                    onViewContributor: { contributorId in shell.open(ContributorDestination(id: contributorId)) },
                    chapterListRequest: $shell.isChapterListRequested
                )
                .environment(\.reportPlayerSheet) { isOpen in shell.playerSheetIsOpen = isOpen }
            }
        }
        // The menu bar's Playback and View commands act on the focused window's shell and player.
        .focusedSceneValue(\.mainShell, shell)
        .focusedSceneValue(\.playerObserver, playerObserver)
        // Closing the book (or anything else that ends the session) takes the player with it.
        .onChange(of: isMiniPlayerShown) { _, isShown in
            if !isShown { shell.isPlayerPresented = false }
        }
        // The shared player's navigation: Go to book, and the held book's Open Inbox for an admin.
        .onChange(of: playerObserver?.bookToOpen) { _, bookId in
            guard let bookId else { return }
            playerObserver?.bookToOpen = nil
            shell.isPlayerPresented = false
            shell.open(BookDestination(id: bookId))
        }
        .onChange(of: playerObserver?.inboxRequested) { _, requested in
            guard requested == true else { return }
            playerObserver?.inboxRequested = false
            shell.isPlayerPresented = false
            shell.open(AdminInboxDestination())
        }
        .onAppear {
            if playerObserver == nil {
                playerObserver = deps.playerObserver
            }
            if libraryObserver == nil {
                libraryObserver = LibraryObserver(viewModel: deps.libraryViewModel)
            }
            if inboxBadge == nil {
                inboxBadge = InboxBadgeObserver(viewModel: deps.createInboxBadgeViewModel())
            }
            if restrictedBooks == nil {
                restrictedBooks = RestrictedBooksObserver(viewModel: deps.createRestrictedBooksViewModel())
            }
            restoreNavigationOnce()
        }
        .onChange(of: sidebarListsSections) { _, showing in
            shell.adaptToSidebarSections(showing: showing)
        }
        .onChange(of: usesSidebar, initial: true) { _, usesSidebar in
            shell.adaptToLayout(usesSidebar: usesSidebar)
        }
        // Discover's "Recently Added" link: Books newest first, for this session only (plan Q6).
        .onChange(of: shell.libraryNewestRequest) { _, _ in libraryObserver?.showNewestFirst() }
        // Home's "Everything in Progress" link: Books filtered to In Progress.
        .onChange(of: shell.libraryInProgressRequest) { _, _ in libraryObserver?.setStatusFilter(.inProgress) }
        .onChange(of: shell.selectedTab) { _, _ in saveNavigation() }
        .onChange(of: shell.paths) { _, _ in saveNavigation() }
        // Every window's shell observes the one router; the claim hands a link to the front window
        // only, so it opens once. `initial: true` covers the link that launched the app.
        .onChange(of: deepLinkRouter.outcome, initial: true) { _, _ in claimDeepLink() }
        // Shade-tap consumer (in-app inbox taps route directly via `shell.route` — they never go
        // through `pending`). `initial: true` covers the cold-launch tap held from before this shell
        // mounted. The router hands a tap to one window only — the one most recently in front.
        .onChange(of: pushTapRouter.pending, initial: true) { _, _ in
            if let outcome = pushTapRouter.claimPending(for: sceneID) { shell.route(outcome) }
        }
        .onChange(of: scenePhase, initial: true) { _, phase in
            guard phase == .active else { return }
            pushTapRouter.sceneBecameActive(sceneID)
            deepLinkRouter.sceneBecameActive(sceneID)
            if let outcome = pushTapRouter.claimPending(for: sceneID) { shell.route(outcome) }
            claimDeepLink()
        }
        .onDisappear {
            pushTapRouter.sceneWentAway(sceneID)
            deepLinkRouter.sceneWentAway(sceneID)
        }
        .alert(
            bookLinkError?.message ?? "",
            isPresented: Binding(get: { bookLinkError != nil }, set: { if !$0 { bookLinkError = nil } })
        ) {
            Button(String(localized: "common.ok"), role: .cancel) {}
        }
    }

    // MARK: - Deep links

    /// Opens a book link in this window when it is the one to take it.
    private func claimDeepLink() {
        guard let claimed = deepLinkRouter.claimShellOutcome(for: sceneID) else { return }
        switch claimed {
        case .openBook(let id): shell.open(BookDestination(id: id))
        case .openSearch: shell.focusSearch()
        case .wrongServer: bookLinkError = .wrongServer
        case .notConnected: bookLinkError = .notConnected
        case .none, .claimInvite: break
        }
    }

    // MARK: - Library

    /// The compact tab bar's one Library tab, or — at regular width — a Library section holding one
    /// entry per section. Verified in a real window on iPadOS 26: the section lists its four entries
    /// in the sidebar and collapses to a single "Library" item in the floating tab bar when the
    /// sidebar is hidden, so no separate Library tab is needed there (and hiding one from the
    /// sidebar hid it from the tab bar too).
    @TabContentBuilder<ShellTab>
    private var libraryTabs: some TabContent<ShellTab> {
        if usesSidebar {
            TabSection(ShellTab.library.title) {
                ForEach(LibraryTab.allCases) { section in
                    SwiftUI.Tab(section.title, systemImage: section.icon, value: ShellTab.librarySection(section)) {
                        tabStack(.librarySection(section)) { libraryView(for: .librarySection(section)) }
                    }
                    .customizationID("listenup.library.\(section.rawValue)")
                    // Each section's count, trailing its name (spec §3.1.5, Q7); `LibrarySidebarBadges` says
                    // why they step aside when the sidebar hides.
                    .badge(sectionBadge(libraryBadges.entry(section)))
                }
            }
            .customizationID("listenup.librarySections")
            .badge(libraryBadges.section)

            // An admin can always reach the inbox, even with nothing held (never stranded). The board's second line,
            // "Waiting for you to release", is the inbox screen's own subtitle: a sidebar entry carries one line.
            if InboxPlacement.showsServerSection(
                isAdmin: inboxBadge?.isAdmin == true,
                showingSections: sidebarListsSections
            ) {
                TabSection(String(localized: "common.server")) {
                    SwiftUI.Tab(ShellTab.adminInbox.title, systemImage: "tray.full", value: ShellTab.adminInbox) {
                        tabStack(.adminInbox) { AdminInboxView() }
                    }
                    .customizationID("listenup.inbox")
                    .badge(libraryBadges.inbox)
                    .tabPlacement(.sidebarOnly)
                }
                .customizationID("listenup.server")
            }
        } else {
            SwiftUI.Tab(ShellTab.library.title, systemImage: "books.vertical.fill", value: ShellTab.library) {
                tabStack(.library) { libraryView(for: .library) }
            }
            .customizationID("listenup.library")
            .badge(heldCount)
        }
    }

    /// A section entry's trailing count; nothing at zero, as an empty mailbox shows none.
    private func sectionBadge(_ count: Int) -> Text? {
        count > 0 ? Text(count.formatted()) : nil
    }

    /// A Library screen for `tab`, sharing this window's library projection. Its section switcher
    /// and the sidebar drive one another through `MainShellModel.selectLibrarySection`.
    private func libraryView(for tab: ShellTab) -> some View {
        LibraryView(
            selectedTab: Binding(
                get: {
                    if case .librarySection(let section) = tab { return section }
                    return shell.librarySection
                },
                set: { shell.selectLibrarySection($0, from: tab) }
            ),
            chrome: LibraryChrome(tab: tab),
            observer: libraryObserver,
            inbox: LibraryInboxEntryModel.make(count: heldCount)
        )
    }

    // MARK: - Tab Builder

    @ViewBuilder
    private func tabStack<Content: View>(_ tab: ShellTab, @ViewBuilder _ content: () -> Content) -> some View {
        NavigationStack(path: pathBinding(tab)) {
            content()
                .navigationDestinations()
                .navigationDestination(for: SearchSeeAllDestination.self) { destination in
                    SeeAllSearchView(destination: destination, path: pathBinding(tab))
                }
                // Registered here (not in `contentDestinations()`) because a tapped row's
                // outcome appends onto THIS tab's path — the same reason SeeAllSearch lives here.
                .navigationDestination(for: NotificationsDestination.self) { _ in
                    NotificationsView { outcome in shell.route(outcome, on: tab) }
                }
        }
        // Applied on the NavigationStack (not its root content) so pushed destinations AND the
        // sheets they present inherit it — lets the Cast & Credits sheet push a contributor onto
        // THIS tab's main stack (a full page) instead of navigating inside the sheet.
        .environment(\.navigateToContributor, { shell.open(ContributorDestination(id: $0)) })
        .environment(\.openLibraryNewestFirst, { shell.openLibraryNewestFirst() })
        .environment(\.openDiscoverLeaderboard, { shell.landOnLeaderboard() })
        .environment(\.openLibraryInProgress, { shell.openLibraryInProgress() })
        .environment(\.openActivitySubject, { shell.open(subject: $0) })
        .environment(\.heroNamespace, heroNamespace)
        .modifier(TabBarSectionsProbe(isShowingSections: $isTabBarShowingSections))
    }

    /// Binding into the shell's per-tab paths, defaulting to an empty path so a missing entry
    /// never traps.
    private func pathBinding(_ tab: ShellTab) -> Binding<NavigationPath> {
        Binding(
            get: { shell.path(for: tab) },
            set: { shell.setPath($0, for: tab) }
        )
    }

    /// Whether a book is loaded (or failed to load) — the mini player shows exactly then.
    private var isMiniPlayerShown: Bool { playerObserver?.isVisible == true }

    /// Brings back the window's tab and stacks the first time the shell appears in this scene.
    private func restoreNavigationOnce() {
        guard !hasRestoredNavigation else { return }
        hasRestoredNavigation = true
        if let storedNavigation { shell.restore(from: storedNavigation) }
        shell.adaptToLayout(usesSidebar: usesSidebar)
    }

    private func saveNavigation() {
        guard hasRestoredNavigation else { return }
        storedNavigation = shell.sceneState()
    }
}

// MARK: - Mini player accessory

/// Attaches the mini player as the tab view's bottom accessory only while a book is loaded.
///
/// iOS 26.1 added `tabViewBottomAccessory(isEnabled:)`, which hides the accessory without
/// rebuilding the tab view. On 26.0 an accessory whose content is empty still draws an empty
/// glass capsule, so the modifier is attached only while there is something to show — at the
/// cost of one tab-view rebuild when a book first loads or closes.
private struct MiniPlayerAccessory<Accessory: View>: ViewModifier {
    let isEnabled: Bool
    @ViewBuilder let accessory: () -> Accessory

    func body(content: Content) -> some View {
        if #available(iOS 26.1, *) {
            content.tabViewBottomAccessory(isEnabled: isEnabled, content: accessory)
        } else if isEnabled {
            content.tabViewBottomAccessory(content: accessory)
        } else {
            content
        }
    }
}

/// Opens on the sidebar instead of the floating tab bar when the shell decided on one (a window
/// that is regular in both directions: an iPad full screen, the open iPhone Duo), from the iOS 27
/// SDK. A Pro Max iPhone in landscape and the closed Duo keep the automatic tab bar, and earlier
/// systems keep the `.sidebarAdaptable` default.
///
/// The tab view's identity follows the placement. Changing `defaultTabBarPlacement` on a live tab view crashed the
/// app when the Duo folded: UIKit rebuilt the tab items inside the trait pass and threw (`-[UITabBarController
/// _tabs_rebuildTabBarItemsAnimated:]`, "insertObject:atIndex: object cannot be nil", iOS 27.1). Deferring the change
/// by a main-actor turn did not help, and neither did keeping the tab set constant, so a placement change builds a
/// fresh tab view instead. The shell model owns the selected tab and every stack, so nothing the person is on is lost.
private struct SidebarFirstTabBar: ViewModifier {
    let usesSidebar: Bool

    /// Whether this system places the sidebar first (`defaultTabBarPlacement`, iOS 27).
    static var isAvailable: Bool {
        if #available(iOS 27.0, *) { true } else { false }
    }

    func body(content: Content) -> some View {
        if #available(iOS 27.0, *) {
            content
                .defaultTabBarPlacement(usesSidebar ? .sidebar : .automatic)
                .id(usesSidebar)
        } else {
            content
        }
    }
}

// MARK: - Profile Routing

/// Routes a `ProfileDestination` to the full self-profile when it targets the signed-in user,
/// and to the lean read-only `ForeignProfileView` for anyone else. Tapping your own avatar in a
/// social surface (leaderboard, activity feed, book readers) all emit `ProfileDestination`, so the
/// self-check lives here — one place — rather than at each tap site.
struct ProfileDestinationView: View {
    let userId: String
    @Environment(CurrentUserObserver.self) private var currentUser

    var body: some View {
        if userId == currentUser.user?.idString {
            UserProfileView()
        } else {
            ForeignProfileView(userId: userId)
        }
    }
}

// MARK: - Navigation actions

extension EnvironmentValues {
    /// Push a contributor's full detail page onto the current tab's main navigation stack. Provided
    /// by `MainTabView`; used by modally-presented content (the Cast & Credits sheet) to open a
    /// contributor as a full page instead of pushing inside the sheet's own stack.
    @Entry var navigateToContributor: (String) -> Void = { _ in }

    /// Open the Library's Books sorted Added, newest first, for this session. Provided by
    /// `MainTabView`; Discover's Recently Added heading uses it.
    @Entry var openLibraryNewestFirst: () -> Void = {}

    /// Open Discover at its leaderboard, on Week (spec §6.4). Provided by `MainTabView`; the stats
    /// card's leaderboard link (Home) uses it.
    @Entry var openDiscoverLeaderboard: () -> Void = {}

    /// Open the Library's Books filtered to In Progress (spec §6.4). Provided by `MainTabView`; Home's Also in
    /// Progress heading uses it.
    @Entry var openLibraryInProgress: () -> Void = {}

    /// Push an activity row's book, shelf or person onto this tab's stack: its context menu and
    /// VoiceOver actions (spec §3.5). Provided by `MainTabView`.
    @Entry var openActivitySubject: (ActivityDestination) -> Void = { _ in }
}

// MARK: - Preview

#Preview {
    MainTabView()
        .environment(CurrentUserObserver())
}
