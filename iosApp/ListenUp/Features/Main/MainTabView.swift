import SwiftUI
@preconcurrency import Shared
import UIKit

/// Root tab view for the main authenticated app experience.
///
/// Structure:
/// - TabView with Home, Library, Discover tabs
/// - Each tab wraps content in NavigationStack
/// - iPad gets sidebar-adaptable style
/// - ZStack overlay for MiniPlayerView (glass mini player)
/// - fullScreenCover for FullScreenPlayerView
struct MainTabView: View {
    @Environment(\.dependencies) private var deps
    @State private var selectedTab: Tab = .home
    @State private var showFullScreenPlayer = false
    @State private var playerCoordinator: PlayerCoordinator?

    init() {
        // Configure tab bar appearance for glass effect with good contrast
        let appearance = UITabBarAppearance()
        appearance.configureWithDefaultBackground()
        appearance.backgroundEffect = UIBlurEffect(style: .systemThinMaterial)
        appearance.backgroundColor = UIColor.systemBackground.withAlphaComponent(0.7)
        UITabBar.appearance().standardAppearance = appearance
        UITabBar.appearance().scrollEdgeAppearance = appearance
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            TabView(selection: $selectedTab) {
                tab(.home, icon: "house.fill") { HomeView() }
                tab(.library, icon: "books.vertical.fill") { LibraryView() }
                tab(.discover, icon: "sparkles") { DiscoverView() }
                tab(.search, icon: "magnifyingglass") { SearchView() }
            }
            // .tabViewStyle(.sidebarAdaptable) // Removed: causes layout issues on some devices

            // Mini player overlay — floats above tab bar
            if let observer = playerCoordinator, observer.isVisible {
                MiniPlayerView(
                    observer: observer,
                    onTap: { showFullScreenPlayer = true }
                )
                .padding(.bottom, 49) // Standard tab bar height
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.35, dampingFraction: 0.7), value: playerCoordinator?.isVisible ?? false)
        .fullScreenCover(isPresented: $showFullScreenPlayer) {
            if let observer = playerCoordinator {
                FullScreenPlayerView(
                    observer: observer,
                    isPresented: $showFullScreenPlayer
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

    private func tab<Content: View>(
        _ tab: Tab,
        icon: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        NavigationStack {
            content()
                .navigationDestinations()
        }
        .tabItem {
            Label(tab.title, systemImage: icon)
        }
        .tag(tab)
        .safeAreaInset(edge: .bottom) {
            if playerCoordinator?.isVisible == true {
                Color.clear.frame(height: MiniPlayerView.height)
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
