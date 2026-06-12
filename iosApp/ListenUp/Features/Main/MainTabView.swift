import SwiftUI
@preconcurrency import Shared

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
                    tabStack(.search) { SearchView() }
                }
            }
            .tabViewStyle(.sidebarAdaptable)
            .tabBarMinimizeBehavior(.onScrollDown)

            if let coordinator = playerCoordinator, coordinator.isVisible {
                PlayerExpansionOverlay(coordinator: coordinator, onViewBookDetails: pushBookDetail)
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
                .safeAreaInset(edge: .bottom) {
                    if playerCoordinator?.isVisible == true {
                        Color.clear.frame(height: miniBarInset)
                    }
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
            .navigationDestination(for: UserProfileDestination.self) { _ in
                UserProfileView()
            }
            .navigationDestination(for: SettingsDestination.self) { _ in
                SettingsView()
            }
    }
}

// MARK: - Preview

#Preview {
    MainTabView()
        .environment(CurrentUserObserver())
}
