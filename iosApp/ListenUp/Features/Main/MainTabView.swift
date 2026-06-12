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

    /// Height reserved below tab content for the collapsed mini bar (bar + clearance).
    private var miniBarInset: CGFloat {
        MiniPlayerBar.barHeight + MiniPlayerBar.tabBarClearance
    }

    var body: some View {
        ZStack {
            TabView(selection: $selectedTab) {
                SwiftUI.Tab(Tab.home.title, systemImage: "house.fill", value: Tab.home) {
                    tabStack { HomeView() }
                }
                SwiftUI.Tab(Tab.library.title, systemImage: "books.vertical.fill", value: Tab.library) {
                    tabStack { LibraryView() }
                }
                SwiftUI.Tab(Tab.discover.title, systemImage: "sparkles", value: Tab.discover) {
                    tabStack { DiscoverView() }
                }
                SwiftUI.Tab(value: Tab.search, role: .search) {
                    tabStack { SearchView() }
                }
            }
            .tabViewStyle(.sidebarAdaptable)
            .tabBarMinimizeBehavior(.onScrollDown)

            if let coordinator = playerCoordinator, coordinator.isVisible {
                PlayerExpansionOverlay(coordinator: coordinator)
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
    private func tabStack<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        NavigationStack {
            content()
                .navigationDestinations()
                .safeAreaInset(edge: .bottom) {
                    if playerCoordinator?.isVisible == true {
                        Color.clear.frame(height: miniBarInset)
                    }
                }
        }
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
