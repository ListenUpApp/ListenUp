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
    @Environment(\.scenePhase) private var scenePhase
    @State private var shell = MainShellModel()
    @State private var playerCoordinator: PlayerCoordinator?
    /// One library projection for every Library tab and sidebar entry in this window.
    @State private var libraryObserver: LibraryObserver?
    @State private var bookLinkError: BookLinkError?
    /// The window's book share links, for every book context menu under the shell.
    @State private var shareLinks = BookShareLinks()
    /// Identifies this window to the process-wide `PushTapRouter`, so a tap lands in one window.
    @State private var sceneID = UUID()
    @State private var hasRestoredNavigation = false

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

    /// A regular-width iPad window shows the sidebar; an iPhone (in either orientation) and a narrow
    /// iPad window (1/3 Split View) the tab bar.
    private var usesSidebar: Bool {
        MainShellModel.usesSidebar(
            horizontalSizeClass: horizontalSizeClass,
            isPhone: UIDevice.current.userInterfaceIdiom == .phone
        )
    }

    var body: some View {
        @Bindable var shell = shell
        TabView(selection: $shell.selectedTab) {
            SwiftUI.Tab(ShellTab.home.title, systemImage: "house.fill", value: ShellTab.home) {
                tabStack(.home) { HomeView() }
            }
            .customizationID("listenup.home")

            libraryTabs

            SwiftUI.Tab(ShellTab.discover.title, systemImage: "sparkles", value: ShellTab.discover) {
                tabStack(.discover) { DiscoverView() }
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
        .task { await shareLinks.load() }
        .tabViewStyle(.sidebarAdaptable)
        .tabViewCustomization($sidebarCustomization)
        // HIG, Tab bars: an attached accessory "like the MiniPlayer in Music" moves inline with
        // the tab bar when it minimizes on scroll.
        .tabBarMinimizeBehavior(.onScrollDown)
        .modifier(MiniPlayerAccessory(isEnabled: isMiniPlayerShown) {
            if let coordinator = playerCoordinator {
                MiniPlayerBar(
                    observer: coordinator,
                    transitionNamespace: playerNamespace,
                    onOpen: { shell.isPlayerPresented = true }
                )
            }
        })
        // The full player is a system full-screen modal (HIG, Modality: "a full-screen modal style
        // for in-depth content"), which brings VoiceOver modality, swipe-down dismissal and the
        // zoom back into the mini player with it.
        .fullScreenCover(isPresented: $shell.isPlayerPresented) {
            if let coordinator = playerCoordinator {
                FullScreenPlayerView(
                    observer: coordinator,
                    onViewDetails: {
                        if let bookId = coordinator.currentBookId { shell.open(BookDestination(id: bookId)) }
                    },
                    onViewSeries: { seriesId in shell.open(SeriesDestination(id: seriesId)) },
                    onViewContributor: { contributorId in shell.open(ContributorDestination(id: contributorId)) }
                )
                .navigationTransition(.zoom(sourceID: PlayerTransition.coverID, in: playerNamespace))
            }
        }
        // The menu bar's Playback and View commands act on the focused window's shell and player.
        .focusedSceneValue(\.mainShell, shell)
        .focusedSceneValue(\.playerCoordinator, playerCoordinator)
        // Closing the book (or anything else that ends the session) takes the player with it.
        .onChange(of: isMiniPlayerShown) { _, isShown in
            if !isShown { shell.isPlayerPresented = false }
        }
        .onAppear {
            if playerCoordinator == nil {
                playerCoordinator = deps.playerCoordinator
            }
            if libraryObserver == nil {
                libraryObserver = LibraryObserver(viewModel: deps.libraryViewModel)
            }
            restoreNavigationOnce()
        }
        .onChange(of: usesSidebar, initial: true) { _, usesSidebar in
            shell.adaptToLayout(usesSidebar: usesSidebar)
        }
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
                }
            }
            .customizationID("listenup.librarySections")
        } else {
            SwiftUI.Tab(ShellTab.library.title, systemImage: "books.vertical.fill", value: ShellTab.library) {
                tabStack(.library) { libraryView(for: .library) }
            }
            .customizationID("listenup.library")
        }
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
            observer: libraryObserver
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
        .environment(\.heroNamespace, heroNamespace)
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
    private var isMiniPlayerShown: Bool { playerCoordinator?.isVisible == true }

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

// MARK: - Profile Routing

/// Routes a `ProfileDestination` to the full self-profile when it targets the signed-in user,
/// and to the lean read-only `ForeignProfileView` for anyone else. Tapping your own avatar in a
/// social surface (leaderboard, activity feed, book readers) all emit `ProfileDestination`, so the
/// self-check lives here — one place — rather than at each tap site.
private struct ProfileDestinationView: View {
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
}

// MARK: - Navigation Destinations

private extension View {
    /// All value-typed navigation destinations for the app shell, split into a content group and an
    /// admin group so neither builder trips the function-length lint.
    func navigationDestinations() -> some View {
        contentDestinations()
            .adminDestinations()
    }

    /// Library/content-facing destinations: books, series, contributors, facets, shelves, profiles,
    /// settings, and storage.
    func contentDestinations() -> some View {
        self
            .navigationDestination(for: BookDestination.self) { destination in
                BookDetailView(bookId: destination.id)
                    .heroDestination(bookCoverHeroID(destination.id))
            }
            .navigationDestination(for: SeriesDestination.self) { destination in
                SeriesDetailView(seriesId: destination.id)
                    .heroDestination(seriesHeroID(destination.id))
            }
            .navigationDestination(for: ContributorDestination.self) { destination in
                ContributorDetailView(contributorId: destination.id)
                    .heroDestination(contributorHeroID(destination.id))
            }
            .navigationDestination(for: ContributorBooksDestination.self) { destination in
                ContributorBooksView(
                    contributorId: destination.contributorId,
                    role: destination.role,
                    contributorName: destination.contributorName,
                    roleDisplayName: destination.roleDisplayName
                )
            }
            .navigationDestination(for: FacetDestination.self) { destination in
                FacetBooksView(kind: destination.kind, facetId: destination.id, facetName: destination.name)
            }
            .navigationDestination(for: GenreDestination.self) { destination in
                GenrePageView(genreId: destination.genreId, genreName: destination.genreName)
            }
            .navigationDestination(for: ShelfDestination.self) { destination in
                ShelfDetailView(shelfId: destination.id)
            }
            .navigationDestination(for: UserProfileDestination.self) { _ in
                UserProfileView()
            }
            .navigationDestination(for: ProfileDestination.self) { destination in
                ProfileDestinationView(userId: destination.userId)
            }
            .navigationDestination(for: SettingsDestination.self) { _ in
                SettingsView()
            }
            .navigationDestination(for: StorageDestination.self) { _ in
                StorageView()
            }
            .navigationDestination(for: DevicesDestination.self) { _ in
                DevicesView()
            }
            .navigationDestination(for: NotificationPrefsDestination.self) { _ in
                NotificationPrefsView()
            }
            .navigationDestination(for: HardcoverDestination.self) { _ in
                HardcoverSettingsView()
            }
            .navigationDestination(for: LicensesDestination.self) { _ in
                LicensesView()
            }
            .navigationDestination(for: LicenseDetailDestination.self) { destination in
                LicenseDetailView(packageName: destination.packageName)
            }
    }

    /// Administration destinations (admin / root only surfaces).
    func adminDestinations() -> some View {
        self
            .navigationDestination(for: AdminDestination.self) { _ in
                AdminView()
            }
            .navigationDestination(for: AdminInboxDestination.self) { _ in
                AdminInboxView()
            }
            .navigationDestination(for: ABSImportDestination.self) { _ in
                ABSImportHubView()
            }
            .navigationDestination(for: AdminCollectionsDestination.self) { _ in
                AdminCollectionsView()
            }
            .navigationDestination(for: AdminCategoriesDestination.self) { _ in
                AdminCategoriesView()
            }
            .navigationDestination(for: AdminCollectionDetailDestination.self) { destination in
                AdminCollectionDetailView(collectionId: destination.collectionId)
            }
            .navigationDestination(for: UserDetailDestination.self) { destination in
                UserDetailView(userId: destination.userId)
            }
            .navigationDestination(for: LibrarySettingsDestination.self) { _ in
                LibrarySettingsView()
            }
            .navigationDestination(for: OrganizeSettingsDestination.self) { _ in
                OrganizeSettingsView()
            }
            .navigationDestination(for: UploadBooksDestination.self) { _ in
                UploadBooksView()
            }
            .navigationDestination(for: AdminBackupsDestination.self) { _ in
                AdminBackupsView()
            }
            .navigationDestination(for: RestoreBackupDestination.self) { destination in
                RestoreBackupView(backupId: destination.backupId)
            }
    }
}

// MARK: - Preview

#Preview {
    MainTabView()
        .environment(CurrentUserObserver())
}
