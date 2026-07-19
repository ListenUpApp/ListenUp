import SwiftUI
import Shared

/// Root tab view for the main authenticated app experience.
///
/// Structure:
/// - Native iOS 26 `TabView` with Home, Library, Discover, and a search-role Search tab
/// - Each tab wraps content in a `NavigationStack`
/// - iPad gets the sidebar-adaptable style; the tab bar minimizes on scroll
/// - `PlayerExpansionOverlay` hosts the mini player and morphs it into the full
///   player under a shared `@Namespace` (replacing the accessory + fullScreenCover)
struct MainTabView: View {
    @Environment(\.dependencies) private var deps
    @Environment(DeepLinkRouter.self) private var deepLinkRouter
    @State private var selectedTab: Tab = .home
    @State private var playerCoordinator: PlayerCoordinator?
    @State private var bookLinkError: BookLinkError?

    /// Per-tab navigation paths so the player overlay can push a destination onto the
    /// *active* tab's stack (and so each tab keeps its own independent history).
    @State private var paths: [Tab: NavigationPath] = [
        .home: NavigationPath(),
        .library: NavigationPath(),
        .discover: NavigationPath(),
        .search: NavigationPath()
    ]

    /// Live bottom safe-area inset *as the tab content sees it* — home indicator plus
    /// the floating tab bar (which tracks the `.onScrollDown` minimize state).
    ///
    /// The iOS 26 floating tab bar reserves space only inside the `TabView`'s own
    /// content safe area — a ZStack sibling like the player overlay never sees it. We
    /// measure it here and hand it to the overlay so the mini player anchors to the
    /// *real* bar, not a fixed guess, in both the full and minimized bar states.
    @State private var tabContentBottomInset: CGFloat = 0

    /// Height reserved below tab content for the collapsed mini bar (bar + clearance).
    private var miniBarInset: CGFloat {
        MiniPlayerBar.barHeight + MiniPlayerBar.tabBarClearance
    }

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

    var body: some View {
        ZStack {
            TabView(selection: $selectedTab) {
                SwiftUI.Tab(Tab.home.title, systemImage: "house.fill", value: Tab.home) {
                    tabStack(.home) { HomeView() }
                }
                SwiftUI.Tab(Tab.library.title, systemImage: "books.vertical.fill", value: Tab.library) {
                    tabStack(.library) { LibraryView() }
                }
                SwiftUI.Tab(Tab.discover.title, systemImage: "sparkles", value: Tab.discover) {
                    tabStack(.discover) { DiscoverView() }
                }
                SwiftUI.Tab(value: Tab.search, role: .search) {
                    tabStack(.search) { SearchView(path: pathBinding(.search)) }
                }
            }
            .tabViewStyle(.sidebarAdaptable)
            .tabBarMinimizeBehavior(.onScrollDown)

            if let coordinator = playerCoordinator, coordinator.isVisible {
                PlayerExpansionOverlay(
                    coordinator: coordinator,
                    tabContentBottomInset: tabContentBottomInset,
                    onViewBookDetails: pushBookDetail,
                    onViewSeries: pushSeries,
                    onViewContributor: pushContributor
                )
            }
        }
        .onAppear {
            if playerCoordinator == nil {
                playerCoordinator = deps.playerCoordinator
            }
        }
        .onChange(of: deepLinkRouter.outcome) { _, outcome in
            switch outcome {
            case .openBook(let id):
                paths[selectedTab, default: NavigationPath()].append(BookDestination(id: id))
                deepLinkRouter.consume()
            case .wrongServer:
                bookLinkError = .wrongServer
                deepLinkRouter.consume()
            case .notConnected:
                bookLinkError = .notConnected
                deepLinkRouter.consume()
            case .none, .claimInvite:
                break
            }
        }
        .alert(item: $bookLinkError) { error in
            Alert(title: Text(error.message))
        }
    }

    // MARK: - Tab Builder

    @ViewBuilder
    private func tabStack<Content: View>(_ tab: Tab, @ViewBuilder _ content: () -> Content) -> some View {
        NavigationStack(path: pathBinding(tab)) {
            content()
                .navigationDestinations()
                .pushedDestination(for: SearchSeeAllDestination.self) { destination in
                    SeeAllSearchView(destination: destination, path: pathBinding(tab))
                }
                // Probe the *system* bottom inset (home indicator + floating tab bar)
                // BEFORE reserving the mini-bar band below, so it measures only the bar.
                .background(tabBarInsetProbe)
                .safeAreaInset(edge: .bottom) {
                    if playerCoordinator?.isVisible == true {
                        Color.clear.frame(height: miniBarInset)
                    }
                }
        }
    }

    /// Reads the tab content's live bottom safe-area inset (home indicator + floating
    /// tab bar) and publishes it for the overlay. Sits behind tab content (zero-sized,
    /// non-interactive) and updates as the bar minimizes/expands on scroll.
    ///
    /// The probe is attached *inside* the same view the sibling `.safeAreaInset` reserves
    /// the `miniBarInset` mini-bar band on, so `proxy.safeAreaInsets.bottom` already
    /// includes that band. We subtract it back out (when the band is actually reserved)
    /// so the published value is the *true* system bar — otherwise the mini player floats
    /// a whole `miniBarInset` (~60pt) too high above the tab bar.
    private var tabBarInsetProbe: some View {
        GeometryReader { proxy in
            Color.clear
                .onGeometryChange(for: CGFloat.self) { _ in
                    proxy.safeAreaInsets.bottom
                } action: { inset in
                    let reservedBand = playerCoordinator?.isVisible == true ? miniBarInset : 0
                    tabContentBottomInset = max(0, inset - reservedBand)
                }
        }
    }

    /// Binding into the per-tab path dictionary, defaulting to an empty path so a
    /// missing entry never traps.
    private func pathBinding(_ tab: Tab) -> Binding<NavigationPath> {
        Binding(
            get: { paths[tab] ?? NavigationPath() },
            set: { paths[tab] = $0 }
        )
    }

    /// Push the book's detail screen onto the currently selected tab's stack — the
    /// destination for the player overlay's "View Book Details" action.
    private func pushBookDetail(_ bookId: String) {
        paths[selectedTab, default: NavigationPath()].append(BookDestination(id: bookId))
    }

    private func pushSeries(_ seriesId: String) {
        paths[selectedTab, default: NavigationPath()].append(SeriesDestination(id: seriesId))
    }

    private func pushContributor(_ contributorId: String) {
        paths[selectedTab, default: NavigationPath()].append(ContributorDestination(id: contributorId))
    }
}

// MARK: - Tab Enum

extension MainTabView {
    enum Tab: Hashable {
        case home
        case library
        case search
        case discover

        var title: String {
            switch self {
            case .home: String(localized: "common.home")
            case .library: String(localized: "common.library")
            case .search: String(localized: "common.search")
            case .discover: String(localized: "common.discover")
            }
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

// MARK: - Navigation Destinations

private extension View {
    /// All value-typed navigation destinations for the app shell, split into a content group and an
    /// admin group so neither builder trips the function-length lint.
    func navigationDestinations() -> some View {
        contentDestinations()
            .adminDestinations()
    }

    /// Registers a pushed navigation destination that reserves the floating mini player's footprint,
    /// so the screen's content scrolls clear of the bar. A tab-root's `.safeAreaInset` doesn't reach
    /// pushed screens, so every destination goes through here to inherit the reservation from one
    /// place — new screens get it for free, and none can silently regress.
    func pushedDestination<D: Hashable, Content: View>(
        for type: D.Type,
        @ViewBuilder destination: @escaping (D) -> Content
    ) -> some View {
        navigationDestination(for: type) { value in
            destination(value).miniPlayerBottomInset()
        }
    }

    /// Library/content-facing destinations: books, series, contributors, facets, shelves, profiles,
    /// settings, and storage.
    func contentDestinations() -> some View {
        self
            .pushedDestination(for: BookDestination.self) { destination in
                BookDetailView(bookId: destination.id)
            }
            .pushedDestination(for: SeriesDestination.self) { destination in
                SeriesDetailView(seriesId: destination.id)
            }
            .pushedDestination(for: ContributorDestination.self) { destination in
                ContributorDetailView(contributorId: destination.id)
            }
            .pushedDestination(for: ContributorBooksDestination.self) { destination in
                ContributorBooksView(
                    contributorId: destination.contributorId,
                    role: destination.role,
                    contributorName: destination.contributorName,
                    roleDisplayName: destination.roleDisplayName
                )
            }
            .pushedDestination(for: FacetDestination.self) { destination in
                FacetBooksView(kind: destination.kind, facetId: destination.id, facetName: destination.name)
            }
            .pushedDestination(for: GenreDestination.self) { destination in
                GenrePageView(genreId: destination.genreId, genreName: destination.genreName)
            }
            .pushedDestination(for: ShelfDestination.self) { destination in
                ShelfDetailView(shelfId: destination.id)
            }
            .pushedDestination(for: ShelfFormDestination.self) { destination in
                CreateEditShelfView(shelfId: destination.shelfId)
            }
            .pushedDestination(for: UserProfileDestination.self) { _ in
                UserProfileView()
            }
            .pushedDestination(for: ProfileDestination.self) { destination in
                ProfileDestinationView(userId: destination.userId)
            }
            .pushedDestination(for: SettingsDestination.self) { _ in
                SettingsView()
            }
            .pushedDestination(for: StorageDestination.self) { _ in
                StorageView()
            }
            .pushedDestination(for: DevicesDestination.self) { _ in
                DevicesView()
            }
            .pushedDestination(for: LicensesDestination.self) { _ in
                LicensesView()
            }
            .pushedDestination(for: LicenseDetailDestination.self) { destination in
                LicenseDetailView(packageName: destination.packageName)
            }
    }

    /// Administration destinations (admin / root only surfaces).
    func adminDestinations() -> some View {
        self
            .pushedDestination(for: AdminDestination.self) { _ in
                AdminView()
            }
            .pushedDestination(for: AdminInboxDestination.self) { _ in
                AdminInboxView()
            }
            .pushedDestination(for: ABSImportDestination.self) { _ in
                ABSImportHubView()
            }
            .pushedDestination(for: AdminCollectionsDestination.self) { _ in
                AdminCollectionsView()
            }
            .pushedDestination(for: AdminCollectionDetailDestination.self) { destination in
                AdminCollectionDetailView(collectionId: destination.collectionId)
            }
            .pushedDestination(for: UserDetailDestination.self) { destination in
                UserDetailView(userId: destination.userId)
            }
            .pushedDestination(for: LibrarySettingsDestination.self) { _ in
                LibrarySettingsView()
            }
            .pushedDestination(for: AdminBackupsDestination.self) { _ in
                AdminBackupsView()
            }
            .pushedDestination(for: RestoreBackupDestination.self) { destination in
                RestoreBackupView(backupId: destination.backupId)
            }
    }
}

// MARK: - Preview

#Preview {
    MainTabView()
        .environment(CurrentUserObserver())
}
