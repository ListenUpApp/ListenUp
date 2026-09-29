import SwiftUI
import Shared

/// Root tab view for the main authenticated app experience.
///
/// Structure:
/// - Native iOS 26 `TabView` with Home, Library, Discover, and a search-role Search tab
/// - Each tab wraps content in a `NavigationStack`
/// - iPad gets the sidebar-adaptable style; the tab bar minimizes on scroll
/// - The mini player is the tab view's bottom accessory; the full player is a `fullScreenCover`
///   that zooms out of the mini player's cover (`PlayerTransition`)
struct MainTabView: View {
    @Environment(\.dependencies) private var deps
    @Environment(DeepLinkRouter.self) private var deepLinkRouter
    @Environment(PushTapRouter.self) private var pushTapRouter
    @State private var selectedTab: Tab = .home
    @State private var playerCoordinator: PlayerCoordinator?
    @State private var isPlayerPresented = false
    @State private var bookLinkError: BookLinkError?

    /// Pairs a list cell with the detail page it zooms into. One namespace for the whole shell so
    /// a hero works from any tab and any list that opens a book, contributor, or series.
    @Namespace private var heroNamespace
    /// Pairs the mini player's cover with the full player that zooms out of it.
    @Namespace private var playerNamespace

    /// Per-tab navigation paths so the full player can push a destination onto the
    /// *active* tab's stack (and so each tab keeps its own independent history).
    @State private var paths: [Tab: NavigationPath] = [
        .home: NavigationPath(),
        .library: NavigationPath(),
        .discover: NavigationPath(),
        .search: NavigationPath()
    ]

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
        // HIG, Tab bars: an attached accessory "like the MiniPlayer in Music" moves inline with
        // the tab bar when it minimizes on scroll.
        .tabBarMinimizeBehavior(.onScrollDown)
        .modifier(MiniPlayerAccessory(isEnabled: isMiniPlayerShown) {
            if let coordinator = playerCoordinator {
                MiniPlayerBar(
                    observer: coordinator,
                    transitionNamespace: playerNamespace,
                    onOpen: { isPlayerPresented = true }
                )
            }
        })
        // The full player is a system full-screen modal (HIG, Modality: "a full-screen modal style
        // for in-depth content"), which brings VoiceOver modality, swipe-down dismissal and the
        // zoom back into the mini player with it.
        .fullScreenCover(isPresented: $isPlayerPresented) {
            if let coordinator = playerCoordinator {
                FullScreenPlayerView(
                    observer: coordinator,
                    onViewDetails: {
                        closePlayer()
                        if let bookId = coordinator.currentBookId { pushBookDetail(bookId) }
                    },
                    onViewSeries: { seriesId in closePlayer(); pushSeries(seriesId) },
                    onViewContributor: { contributorId in closePlayer(); pushContributor(contributorId) }
                )
                .navigationTransition(.zoom(sourceID: PlayerTransition.coverID, in: playerNamespace))
            }
        }
        // Closing the book (or anything else that ends the session) takes the player with it.
        .onChange(of: isMiniPlayerShown) { _, isShown in
            if !isShown { closePlayer() }
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
        // Shade-tap consumer (in-app inbox taps append directly via `routeNotificationTap` —
        // they never go through `pending`). `initial: true` covers the cold-launch tap held
        // from before this shell mounted.
        .onChange(of: pushTapRouter.pending, initial: true) { _, pending in
            guard let pending else { return }
            routeNotificationTap(pending, on: selectedTab)
            pushTapRouter.consume()
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
                .navigationDestination(for: SearchSeeAllDestination.self) { destination in
                    SeeAllSearchView(destination: destination, path: pathBinding(tab))
                }
                // Registered here (not in `contentDestinations()`) because a tapped row's
                // outcome appends onto THIS tab's path — the same reason SeeAllSearch lives here.
                .navigationDestination(for: NotificationsDestination.self) { _ in
                    NotificationsView { outcome in routeNotificationTap(outcome, on: tab) }
                }
        }
        // Applied on the NavigationStack (not its root content) so pushed destinations AND the
        // sheets they present inherit it — lets the Cast & Credits sheet push a contributor onto
        // THIS tab's main stack (a full page) instead of navigating inside the sheet.
        .environment(\.navigateToContributor, pushContributor)
        .environment(\.heroNamespace, heroNamespace)
    }

    /// Binding into the per-tab path dictionary, defaulting to an empty path so a
    /// missing entry never traps.
    private func pathBinding(_ tab: Tab) -> Binding<NavigationPath> {
        Binding(
            get: { paths[tab] ?? NavigationPath() },
            set: { paths[tab] = $0 }
        )
    }

    /// Whether a book is loaded (or failed to load) — the mini player shows exactly then.
    private var isMiniPlayerShown: Bool { playerCoordinator?.isVisible == true }

    private func closePlayer() { isPlayerPresented = false }

    /// Push the book's detail screen onto the currently selected tab's stack — the
    /// destination for the full player's "Go to book" action.
    private func pushBookDetail(_ bookId: String) {
        paths[selectedTab, default: NavigationPath()].append(BookDestination(id: bookId))
    }

    private func pushSeries(_ seriesId: String) {
        paths[selectedTab, default: NavigationPath()].append(SeriesDestination(id: seriesId))
    }

    private func pushContributor(_ contributorId: String) {
        paths[selectedTab, default: NavigationPath()].append(ContributorDestination(id: contributorId))
    }

    /// Where a notification tap lands: append the outcome's destination onto the given tab's
    /// stack. In-app inbox taps pass the inbox's own tab; the shade consumer passes the selected
    /// tab. `.none` (unknown types, campfire until #1065, decision notices) stays put.
    private func routeNotificationTap(_ outcome: NotificationTapOutcome, on tab: Tab) {
        switch outcome {
        case .book(let id):
            paths[tab, default: NavigationPath()].append(BookDestination(id: id))
        case .profile(let userId):
            paths[tab, default: NavigationPath()].append(ProfileDestination(userId: userId))
        case .adminApprovals:
            paths[tab, default: NavigationPath()].append(AdminDestination())
        case .none:
            break
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
            .navigationDestination(for: ShelfFormDestination.self) { destination in
                CreateEditShelfView(shelfId: destination.shelfId)
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
