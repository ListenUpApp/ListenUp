import SwiftUI

/// Where the tab shell can stand: one of the tab-bar tabs, or — in the iPad sidebar — one of the
/// Library sections or the sidebar-only Downloads and Settings.
///
/// `Codable` so the selection survives in `@SceneStorage` (HIG, Multitasking: "your app … always
/// needs to be prepared to save and restore their context").
enum ShellTab: Hashable, Codable {
    case home
    /// The single Library tab of the compact tab bar; its section is `MainShellModel.librarySection`.
    case library
    /// One Library section as its own sidebar entry.
    case librarySection(LibraryTab)
    case discover
    case search
    /// Sidebar only: the downloads manager (`StorageView`).
    case downloads
    /// Sidebar only: Settings.
    case settings
    /// Sidebar only, admins only: the inbox, under Server (spec §3.1.5).
    case adminInbox

    var title: String {
        switch self {
        case .home: String(localized: "common.home")
        case .library: String(localized: "common.library")
        case .librarySection(let section): section.title
        case .discover: String(localized: "common.discover")
        case .search: String(localized: "common.search")
        case .downloads: String(localized: "settings.downloads")
        case .settings: String(localized: "common.settings")
        case .adminInbox: String(localized: "common.inbox")
        }
    }

    /// Whether the tab exists only in the sidebar; a compact tab bar has no place for it.
    var isSidebarOnly: Bool {
        switch self {
        case .librarySection, .downloads, .settings, .adminInbox: true
        case .home, .library, .discover, .search: false
        }
    }
}

/// The tab shell's navigation state for one window: the selected tab, each tab's stack, the full
/// player's presentation and the Library section. An `@Observable` value rather than view state
/// so every routing rule — a deep link closing the player, the compact ↔ sidebar hand-over, the
/// scene-storage round trip — is a tested method, not a closure buried in `MainTabView`.
@Observable
@MainActor
final class MainShellModel {
    var selectedTab: ShellTab = .home
    /// Per-tab navigation paths, so each tab keeps its own history.
    private(set) var paths: [ShellTab: NavigationPath] = [:]
    /// Whether the full-screen player covers the shell.
    var isPlayerPresented = false
    /// Set by Show Chapters (⇧⌘C); the player shows its chapters (the panel on a wide window, the sheet otherwise) and
    /// clears it.
    var isChapterListRequested = false
    /// Whether one of the full player's sheets, or its PDF reader, is open: Esc closes that first, so Close Player waits.
    var playerSheetIsOpen = false
    /// The Library section the compact Library tab shows (and the sidebar remembers).
    var librarySection: LibraryTab = .books
    /// Bumped by ⌘F; the Search screen focuses its field on every change.
    private(set) var searchFocusRequest = 0
    /// Bumped by the stats card's leaderboard link; Discover lands on Week with the board in view (spec §6.4).
    private(set) var leaderboardLandingRequest = 0
    /// Bumped by Discover's "Recently Added" link; Library shows Books newest first for this session.
    private(set) var libraryNewestRequest = 0
    /// Bumped by Home's "Also in Progress" link; Library shows Books filtered to In Progress (spec §6.4).
    private(set) var libraryInProgressRequest = 0
    /// Whether the window shows the sidebar (one entry per Library section) or the compact tab bar.
    private(set) var usesSidebar = false

    // MARK: - Paths

    func path(for tab: ShellTab) -> NavigationPath { paths[tab] ?? NavigationPath() }

    func setPath(_ path: NavigationPath, for tab: ShellTab) { paths[tab] = path }

    /// Pushes `destination` onto `tab`'s stack (the selected tab by default), closing the full
    /// player first so the destination is what the person sees, not a cover they must dismiss.
    /// Deep links, notification taps and the player's own "Go to book" all land here.
    ///
    /// `Codable` in the signature, not only on the types: `NavigationPath.append` keeps an element
    /// encodable only when the *static* type is `Codable`, and a path holding one opaque element
    /// cannot be saved to scene storage at all.
    func open<Destination: Hashable & Codable>(_ destination: Destination, on tab: ShellTab? = nil) {
        isPlayerPresented = false
        paths[tab ?? selectedTab, default: NavigationPath()].append(destination)
    }

    /// Opens an activity row's book, shelf or person on the selected tab.
    func open(subject: ActivityDestination) {
        switch subject {
        case .book(let id): open(BookDestination(id: id))
        case .shelf(let id): open(ShelfDestination(id: id))
        case .profile(let userId): open(ProfileDestination(userId: userId))
        }
    }

    /// Where a notification tap lands: the outcome's destination on `tab`. `.none` stays put.
    func route(_ outcome: NotificationTapOutcome, on tab: ShellTab? = nil) {
        switch outcome {
        case .book(let id): open(BookDestination(id: id), on: tab)
        case .profile(let userId): open(ProfileDestination(userId: userId), on: tab)
        case .adminApprovals: open(AdminDestination(focus: .pendingRegistrations), on: tab)
        case .none: break
        }
    }

    /// Selects a tab from the menu bar. Library means the remembered section's entry in the sidebar.
    func select(_ tab: ShellTab) {
        isPlayerPresented = false
        selectedTab = (tab == .library && usesSidebar) ? .librarySection(librarySection) : tab
    }

    /// ⌘0: show the full player.
    func showPlayer() {
        isPlayerPresented = true
    }

    /// ⇧⌘C: show the full player, with its chapters.
    func showPlayerChapters() {
        isPlayerPresented = true
        isChapterListRequested = true
    }

    /// Esc: close the full player.
    func closePlayer() {
        isPlayerPresented = false
    }

    /// ⌘F: show Search and put the cursor in its field.
    func focusSearch() {
        isPlayerPresented = false
        selectedTab = .search
        searchFocusRequest += 1
    }

    // MARK: - Landings

    /// Opens Discover at its leaderboard, on Week (spec §6.4). Discover answers each bump once.
    func landOnLeaderboard() {
        isPlayerPresented = false
        selectedTab = .discover
        leaderboardLandingRequest += 1
    }

    /// Opens the Library's Books sorted Added, newest first, for this session (spec §6.3, plan Q6).
    func openLibraryNewestFirst() {
        isPlayerPresented = false
        librarySection = .books
        selectedTab = usesSidebar ? .librarySection(.books) : .library
        libraryNewestRequest += 1
    }

    /// Opens the Library's Books filtered to In Progress (spec §6.4). The filter is the Library's session state.
    func openLibraryInProgress() {
        isPlayerPresented = false
        librarySection = .books
        selectedTab = usesSidebar ? .librarySection(.books) : .library
        libraryInProgressRequest += 1
    }

    // MARK: - Library section

    /// The section binding for a Library screen shown under `tab`. The compact tab edits
    /// `librarySection`; a sidebar section entry hands the selection to the sibling entry, so the
    /// sidebar highlight follows the in-screen switcher.
    func selectLibrarySection(_ section: LibraryTab, from tab: ShellTab) {
        librarySection = section
        if case .librarySection = tab { selectedTab = .librarySection(section) }
    }

    // MARK: - Compact tab bar ↔ sidebar

    /// Whether the shell shows the sidebar's tabs (one entry per Library section, plus Downloads and
    /// Settings) rather than the compact tab bar's.
    ///
    /// The sidebar is for a window that is regular in both directions: an iPad full screen or the
    /// open iPhone Duo (7.6 inches, regular by regular). A Plus or Pro Max iPhone in landscape also
    /// reports a regular width, but its height is compact, and `.sidebarAdaptable` keeps a tab bar
    /// there, so the sidebar's tab set overflowed into "More" and buried Search, Discover and
    /// Narrators (HIG, Tab bars: keep the tab bar's tabs the app's top-level sections). Deciding from
    /// the two size classes rather than the device idiom follows HIG, Layout: determine layout from
    /// size classes, not device type.
    static func usesSidebar(
        horizontalSizeClass: UserInterfaceSizeClass?,
        verticalSizeClass: UserInterfaceSizeClass?
    ) -> Bool {
        horizontalSizeClass == .regular && verticalSizeClass == .regular
    }

    /// Re-homes the selection when the window crosses between the compact tab bar and the sidebar.
    ///
    /// The compact bar has one Library tab; the sidebar has one entry per section, plus Downloads
    /// and Settings. Crossing keeps the person where they were: the Library stack moves between the
    /// single tab and the section's entry, and a sidebar-only screen reopens on Home's stack.
    func adaptToLayout(usesSidebar: Bool) {
        self.usesSidebar = usesSidebar
        if usesSidebar {
            guard selectedTab == .library else { return }
            let target = ShellTab.librarySection(librarySection)
            movePath(from: .library, to: target)
            selectedTab = target
            return
        }
        switch selectedTab {
        case .librarySection(let section):
            librarySection = section
            movePath(from: selectedTab, to: .library)
            selectedTab = .library
        case .downloads:
            selectedTab = .home
            paths[.home, default: NavigationPath()].append(StorageDestination())
        case .settings:
            selectedTab = .home
            paths[.home, default: NavigationPath()].append(SettingsDestination())
        case .adminInbox:
            selectedTab = .home
            paths[.home, default: NavigationPath()].append(AdminInboxDestination())
        case .home, .library, .discover, .search:
            break
        }
    }

    /// Re-homes the selection when the sidebar hides while the inbox is open: Server › Inbox leaves the tab bar with it
    /// (`InboxPlacement.showsServerSection`), so the inbox reopens on Library's Books, the tab that now leads to it.
    func adaptToSidebarSections(showing: Bool) {
        guard !showing, selectedTab == .adminInbox else { return }
        librarySection = .books
        selectedTab = .librarySection(.books)
        paths[.librarySection(.books), default: NavigationPath()].append(AdminInboxDestination())
    }

    private func movePath(from source: ShellTab, to target: ShellTab) {
        guard let moving = paths[source], !moving.isEmpty else { return }
        paths[target] = moving
        paths[source] = NavigationPath()
    }

    // MARK: - Scene storage

    /// The window's navigation as `@SceneStorage` data. Paths whose destinations are not all
    /// `Codable` are left out rather than failing the whole snapshot.
    func sceneState() -> Data? {
        var encodedPaths: [ShellSceneState.Entry] = []
        for (tab, path) in paths {
            guard !path.isEmpty, let codable = path.codable else { continue }
            encodedPaths.append(.init(tab: tab, path: codable))
        }
        let state = ShellSceneState(
            selectedTab: selectedTab,
            librarySection: librarySection,
            paths: encodedPaths
        )
        return try? JSONEncoder().encode(state)
    }

    /// Restores a snapshot from `sceneState()`. Unreadable data — an older format, a destination
    /// that no longer exists — leaves the shell at its defaults.
    func restore(from data: Data) {
        guard let state = try? JSONDecoder().decode(ShellSceneState.self, from: data) else { return }
        selectedTab = state.selectedTab
        librarySection = state.librarySection
        paths = Dictionary(
            state.paths.map { ($0.tab, NavigationPath($0.path)) },
            uniquingKeysWith: { _, last in last }
        )
    }
}

/// The `@SceneStorage` form of `MainShellModel`.
struct ShellSceneState: Codable {
    struct Entry: Codable {
        let tab: ShellTab
        let path: NavigationPath.CodableRepresentation
    }

    let selectedTab: ShellTab
    let librarySection: LibraryTab
    let paths: [Entry]
}
