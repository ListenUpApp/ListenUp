import SwiftUI
import Testing
@testable import ListenUp

@MainActor
@Suite("Main shell navigation")
struct MainShellModelTests {
    // MARK: - Sidebar or tab bar

    @Test func anIPadAtRegularWidthUsesTheSidebar() {
        #expect(MainShellModel.usesSidebar(horizontalSizeClass: .regular, isPhone: false))
    }

    @Test func aNarrowIPadWindowUsesTheTabBar() {
        #expect(!MainShellModel.usesSidebar(horizontalSizeClass: .compact, isPhone: false))
    }

    /// A Plus or Pro Max iPhone in landscape reports a regular width, but an iPhone has no sidebar:
    /// taking the sidebar's tab set there flattened the Library sections into the tab bar and pushed
    /// Search, Discover and Narrators into "More" (Pass 7 Simulator matrix).
    @Test func anIPhoneInLandscapeKeepsTheTabBar() {
        #expect(!MainShellModel.usesSidebar(horizontalSizeClass: .regular, isPhone: true))
    }

    // MARK: - Opening destinations

    @Test func aDeepLinkWhileThePlayerIsOpenClosesItAndPushesUnderneath() {
        let shell = MainShellModel()
        shell.selectedTab = .discover
        shell.isPlayerPresented = true

        shell.open(BookDestination(id: "b1"))

        #expect(shell.isPlayerPresented == false)
        #expect(shell.path(for: .discover).count == 1)
        #expect(shell.path(for: .home).isEmpty)
    }

    @Test func aNotificationTapClosesThePlayerAndLandsOnTheGivenTab() {
        let shell = MainShellModel()
        shell.isPlayerPresented = true

        shell.route(.profile(userId: "u1"), on: .library)

        #expect(shell.isPlayerPresented == false)
        #expect(shell.path(for: .library).count == 1)
    }

    @Test func anUnroutableTapLeavesThePlayerOpen() {
        let shell = MainShellModel()
        shell.isPlayerPresented = true

        shell.route(.none)

        #expect(shell.isPlayerPresented == true)
        #expect(shell.path(for: .home).isEmpty)
    }

    // The two tests above prove a tap lands somewhere; these prove where. A swapped destination —
    // the admin tap opening the book inbox, say — still pushes exactly one screen.
    @Test func aBookTapOpensThatBook() {
        let shell = MainShellModel()
        shell.route(.book(id: "b1"), on: .home)
        #expect(encoded(shell.path(for: .home)) == encoded(NavigationPath([BookDestination(id: "b1")])))
    }

    @Test func aProfileTapOpensThatListenersProfile() {
        let shell = MainShellModel()
        shell.route(.profile(userId: "u1"), on: .home)
        #expect(encoded(shell.path(for: .home)) == encoded(NavigationPath([ProfileDestination(userId: "u1")])))
    }

    /// Administration, not the admin inbox: the inbox holds freshly scanned books, and the pending
    /// registrations — and their approve/deny controls — are on Administration.
    @Test func anApprovalTapOpensAdministration() {
        let shell = MainShellModel()
        shell.route(.adminApprovals, on: .home)
        #expect(encoded(shell.path(for: .home)) == encoded(NavigationPath([AdminDestination()])))
    }

    @Test func findSelectsSearchAndRequestsFocus() {
        let shell = MainShellModel()
        shell.isPlayerPresented = true

        shell.focusSearch()
        shell.focusSearch()

        #expect(shell.selectedTab == .search)
        #expect(shell.searchFocusRequest == 2)
        #expect(shell.isPlayerPresented == false)
    }

    @Test func theMenuBarsLibraryItemOpensTheRememberedSectionInTheSidebar() {
        let sidebar = MainShellModel()
        sidebar.adaptToLayout(usesSidebar: true)
        sidebar.librarySection = .authors
        sidebar.select(.library)
        #expect(sidebar.selectedTab == .librarySection(.authors))

        let compact = MainShellModel()
        compact.adaptToLayout(usesSidebar: false)
        compact.select(.library)
        #expect(compact.selectedTab == .library)
    }

    // MARK: - Library sections

    @Test func theSidebarSwitcherMovesTheSidebarSelection() {
        let shell = MainShellModel()
        shell.selectedTab = .librarySection(.books)

        shell.selectLibrarySection(.narrators, from: .librarySection(.books))

        #expect(shell.selectedTab == .librarySection(.narrators))
        #expect(shell.librarySection == .narrators)
    }

    @Test func theCompactSwitcherKeepsTheOneLibraryTab() {
        let shell = MainShellModel()
        shell.selectedTab = .library

        shell.selectLibrarySection(.series, from: .library)

        #expect(shell.selectedTab == .library)
        #expect(shell.librarySection == .series)
    }

    // MARK: - Compact ↔ sidebar

    @Test func widenedLibraryBecomesItsSidebarSectionAndKeepsItsStack() {
        let shell = MainShellModel()
        shell.selectedTab = .library
        shell.librarySection = .authors
        shell.open(ContributorDestination(id: "c1"))

        shell.adaptToLayout(usesSidebar: true)

        #expect(shell.selectedTab == .librarySection(.authors))
        #expect(shell.path(for: .librarySection(.authors)).count == 1)
        #expect(shell.path(for: .library).isEmpty)
    }

    @Test func narrowedSidebarSectionBecomesTheLibraryTab() {
        let shell = MainShellModel()
        shell.selectedTab = .librarySection(.series)
        shell.open(SeriesDestination(id: "s1"))

        shell.adaptToLayout(usesSidebar: false)

        #expect(shell.selectedTab == .library)
        #expect(shell.librarySection == .series)
        #expect(shell.path(for: .library).count == 1)
    }

    @Test func narrowedSidebarOnlyScreensReopenOnHome() {
        let settings = MainShellModel()
        settings.selectedTab = .settings
        settings.adaptToLayout(usesSidebar: false)
        #expect(settings.selectedTab == .home)
        #expect(settings.path(for: .home).count == 1)

        let downloads = MainShellModel()
        downloads.selectedTab = .downloads
        downloads.adaptToLayout(usesSidebar: false)
        #expect(downloads.selectedTab == .home)
        #expect(downloads.path(for: .home).count == 1)
    }

    @Test func tabsThatExistInBothLayoutsStayPut() {
        let shell = MainShellModel()
        shell.selectedTab = .discover
        shell.adaptToLayout(usesSidebar: true)
        #expect(shell.selectedTab == .discover)
        shell.adaptToLayout(usesSidebar: false)
        #expect(shell.selectedTab == .discover)
    }

    // MARK: - Scene storage

    @Test func sceneStateRoundTripsTheTabTheSectionAndEveryStack() throws {
        let saved = MainShellModel()
        saved.selectedTab = .librarySection(.series)
        saved.librarySection = .series
        saved.open(SeriesDestination(id: "s1"))
        saved.open(BookDestination(id: "b1"))
        saved.open(FacetDestination(kind: .mood, id: "m1", name: "Cosy"), on: .home)
        saved.open(SearchSeeAllDestination(query: "dune", type: .series), on: .search)

        let data = try #require(saved.sceneState())
        let restored = MainShellModel()
        restored.restore(from: data)

        #expect(restored.selectedTab == .librarySection(.series))
        #expect(restored.librarySection == .series)
        // A decoded path resolves its items lazily and never compares `==` to an eager one, so the
        // stacks are compared by what they encode to.
        for tab in [ShellTab.librarySection(.series), .home, .search] {
            #expect(restored.path(for: tab).count == saved.path(for: tab).count, "\(tab)")
            #expect(encoded(restored.path(for: tab)) == encoded(saved.path(for: tab)), "\(tab)")
        }
        #expect(restored.path(for: .discover).isEmpty)
    }

    /// A path's encoded form — type names and each destination's JSON, with keys sorted, since a
    /// destination's own encoding does not fix its key order.
    private func encoded(_ path: NavigationPath) -> [String]? {
        guard let codable = path.codable,
              let data = try? JSONEncoder().encode(codable),
              let parts = try? JSONDecoder().decode([String].self, from: data)
        else { return nil }
        return parts.map { part in
            guard let object = try? JSONSerialization.jsonObject(with: Data(part.utf8)),
                  let sorted = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
            else { return part }
            return String(bytes: sorted, encoding: .utf8) ?? part
        }
    }

    @Test func unreadableSceneStateLeavesTheDefaults() {
        let shell = MainShellModel()
        shell.restore(from: Data("not a snapshot".utf8))
        #expect(shell.selectedTab == .home)
        #expect(shell.path(for: .home).isEmpty)
    }
}
