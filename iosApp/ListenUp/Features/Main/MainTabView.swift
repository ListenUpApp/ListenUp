import SwiftUI
@preconcurrency import Shared

/// Root tab view for the main authenticated app experience.
///
/// Structure:
/// - Native iOS 26 `TabView` with Home, Library, Discover, and a search-role Search tab
/// - Each tab wraps content in a `NavigationStack`
/// - iPad gets the sidebar-adaptable style; the tab bar minimizes on scroll
/// - `tabViewBottomAccessory` hosts the Liquid Glass mini player
/// - `fullScreenCover` presents the full-screen player
struct MainTabView: View {
    @Environment(\.dependencies) private var deps
    @State private var selectedTab: Tab = .home
    @State private var showFullScreenPlayer = false
    @State private var playerCoordinator: PlayerCoordinator?

    var body: some View {
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
        .tabViewBottomAccessory {
            if let coordinator = playerCoordinator, coordinator.isVisible {
                MiniPlayerView(observer: coordinator, onTap: { showFullScreenPlayer = true })
            }
        }
        .fullScreenCover(isPresented: $showFullScreenPlayer) {
            if let coordinator = playerCoordinator {
                FullScreenPlayerView(observer: coordinator, isPresented: $showFullScreenPlayer)
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
