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

    var title: String {
        switch self {
        case .home: String(localized: "common.home")
        case .library: String(localized: "common.library")
        case .librarySection(let section): section.title
        case .discover: String(localized: "common.discover")
        case .search: String(localized: "common.search")
        case .downloads: String(localized: "settings.downloads")
        case .settings: String(localized: "common.settings")
        }
    }

    /// Whether the tab exists only in the sidebar; a compact tab bar has no place for it.
    var isSidebarOnly: Bool {
        switch self {
        case .librarySection, .downloads, .settings: true
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
    /// The Library section the compact Library tab shows (and the sidebar remembers).
    var librarySection: LibraryTab = .books
    /// Bumped by ⌘F; the Search screen focuses its field on every change.
    private(set) var searchFocusRequest = 0
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

    /// ⌘F: show Search and put the cursor in its field.
    func focusSearch() {
        isPlayerPresented = false
        selectedTab = .search
        searchFocusRequest += 1
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
    /// Only an iPad (or a future Mac) window at regular width has a sidebar to put them in. A Plus or
    /// Pro Max iPhone in landscape also reports a regular width, but `.sidebarAdaptable` keeps a tab
    /// bar on iPhone, so the sidebar's tab set there overflowed into "More" and buried Search,
    /// Discover and Narrators (HIG, Tab bars: keep the tab bar's tabs the app's top-level sections).
    static func usesSidebar(horizontalSizeClass: UserInterfaceSizeClass?, isPhone: Bool) -> Bool {
        horizontalSizeClass == .regular && !isPhone
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
        case .home, .library, .discover, .search:
            break
        }
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
