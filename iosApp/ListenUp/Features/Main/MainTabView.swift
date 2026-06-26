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
    @State private var selectedTab: Tab = .home
    @State private var playerCoordinator: PlayerCoordinator?

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
                    onViewBookDetails: pushBookDetail
                )
            }
        }
        .onAppear {
            if playerCoordinator == nil {
                playerCoordinator = deps.playerCoordinator
            }
        }
    }

    // MARK: - Tab Builder

    @ViewBuilder
    private func tabStack<Content: View>(_ tab: Tab, @ViewBuilder _ content: () -> Content) -> some View {
        NavigationStack(path: pathBinding(tab)) {
            content()
                .navigationDestinations()
                .navigationDestination(for: SearchSeeAllDestination.self) { destination in
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

// MARK: - Navigation Destinations

private extension View {
    func navigationDestinations() -> some View {
        self
            .navigationDestination(for: BookDestination.self) { destination in
                BookDetailView(bookId: destination.id)
            }
            .navigationDestination(for: SeriesDestination.self) { destination in
                SeriesDetailView(seriesId: destination.id)
            }
            .navigationDestination(for: ContributorDestination.self) { destination in
                ContributorDetailView(contributorId: destination.id)
            }
            .navigationDestination(for: TagDestination.self) { destination in
                TagDetailView(tagId: destination.id)
            }
            .navigationDestination(for: ShelfDestination.self) { destination in
                ShelfDetailView(shelfId: destination.id)
            }
            .navigationDestination(for: ShelfFormDestination.self) { destination in
                CreateEditShelfView(shelfId: destination.shelfId)
            }
            .navigationDestination(for: UserProfileDestination.self) { _ in
                UserProfileView()
            }
            .navigationDestination(for: SettingsDestination.self) { _ in
                SettingsView()
            }
            .navigationDestination(for: AdminDestination.self) { _ in
                AdminView()
            }
            .navigationDestination(for: AdminInboxDestination.self) { _ in
                AdminInboxView()
            }
            .navigationDestination(for: ABSImportDestination.self) { _ in
                ABSImportHubView()
            }
            .navigationDestination(for: DevicesDestination.self) { _ in
                DevicesView()
            }
            .navigationDestination(for: LicensesDestination.self) { _ in
                LicensesView()
            }
            .navigationDestination(for: LicenseDetailDestination.self) { destination in
                LicenseDetailView(packageName: destination.packageName)
            }
            .navigationDestination(for: AdminCollectionsDestination.self) { _ in
                AdminCollectionsView()
            }
            .navigationDestination(for: AdminCollectionDetailDestination.self) { destination in
                AdminCollectionDetailView(collectionId: destination.collectionId)
            }
    }
}

// MARK: - Preview

#Preview {
    MainTabView()
        .environment(CurrentUserObserver())
}
