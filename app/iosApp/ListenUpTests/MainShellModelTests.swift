import SwiftUI
import Testing
@testable import ListenUp

@MainActor
@Suite("Main shell navigation")
struct MainShellModelTests {
    // MARK: - Sidebar or tab bar

    @Test func anIPadFullScreenUsesTheSidebar() {
        #expect(MainShellModel.usesSidebar(horizontalSizeClass: .regular, verticalSizeClass: .regular))
    }

    @Test func aNarrowIPadWindowUsesTheTabBar() {
        #expect(!MainShellModel.usesSidebar(horizontalSizeClass: .compact, verticalSizeClass: .regular))
    }

    /// A Plus or Pro Max iPhone in landscape reports a regular width but a compact height: it has no
    /// room for a sidebar, and taking the sidebar's tab set there flattened the Library sections into
    /// the tab bar and pushed Search, Discover and Narrators into "More" (Pass 7 Simulator matrix).
    @Test func aProMaxIPhoneInLandscapeKeepsTheTabBar() {
        #expect(!MainShellModel.usesSidebar(horizontalSizeClass: .regular, verticalSizeClass: .compact))
    }

    /// The open iPhone Duo reports regular by regular, the same classes as an iPad.
    @Test func theOpenIPhoneDuoUsesTheSidebar() {
        #expect(MainShellModel.usesSidebar(horizontalSizeClass: .regular, verticalSizeClass: .regular))
    }

    @Test func theClosedIPhoneDuoInLandscapeKeepsTheTabBar() {
        #expect(!MainShellModel.usesSidebar(horizontalSizeClass: .compact, verticalSizeClass: .compact))
    }

    @Test func anUnknownSizeClassKeepsTheTabBar() {
        #expect(!MainShellModel.usesSidebar(horizontalSizeClass: nil, verticalSizeClass: .regular))
        #expect(!MainShellModel.usesSidebar(horizontalSizeClass: .regular, verticalSizeClass: nil))
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

    /// An edit sheet keeps its draft while a push tap or deep link arrives, because outside navigation
    /// touches nothing a sheet hangs from: it appends one destination to one tab's stack and never
    /// changes the selected tab, other tabs' stacks, or any presentation but the full player. The
    /// pushed screen waits under the sheet. If this ever starts selecting a tab or resetting a stack,
    /// an open edit sheet can be torn down with its draft and the shell needs a "work is held" flag.
    @Test func anOutsideNavigationOnlyAppendsToOneStackAndLeavesTheRestOfTheShellAlone() {
        let shell = MainShellModel()
        shell.selectedTab = .library
        shell.librarySection = .series
        shell.open(SeriesDestination(id: "s1"), on: .library)
        let before = shell.path(for: .library).count

        shell.route(.book(id: "b1"))

        #expect(shell.selectedTab == .library)
        #expect(shell.librarySection == .series)
        #expect(shell.path(for: .library).count == before + 1)
        for other in [ShellTab.home, .discover, .search, .downloads, .settings] {
            #expect(shell.path(for: other).isEmpty)
        }
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
    /// registrations — and their approve/deny controls — are on Administration. It opens on them, not
    /// at the server settings at the top, so the request the person tapped is what they see.
    @Test func anApprovalTapOpensAdministrationOnThePendingRegistrations() {
        let shell = MainShellModel()
        shell.route(.adminApprovals, on: .home)
        let expected = NavigationPath([AdminDestination(focus: .pendingRegistrations)])
        #expect(encoded(shell.path(for: .home)) == encoded(expected))
        #expect(encoded(shell.path(for: .home)) != encoded(NavigationPath([AdminDestination()])))
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

    @Test func adminInboxIsSidebarOnly() {
        #expect(ShellTab.adminInbox.isSidebarOnly)
        #expect(ShellTab.adminInbox.title == String(localized: "common.inbox"))
    }

    /// Narrowing the window while the inbox is open keeps the admin in it: it reopens on Home's stack, as Settings does.
    @Test func narrowedInboxReopensOnHome() {
        let shell = MainShellModel()
        shell.adaptToLayout(usesSidebar: true)
        shell.selectedTab = .adminInbox
        shell.adaptToLayout(usesSidebar: false)
        #expect(shell.selectedTab == .home)
        #expect(shell.path(for: .home).count == 1)
    }

    /// Hiding the sidebar takes Server › Inbox with it, so an open inbox moves to Library's Books, the tab now badged.
    @Test func hidingTheSidebarOnTheInboxKeepsTheInboxOpenUnderLibrary() {
        let shell = MainShellModel()
        shell.adaptToLayout(usesSidebar: true)
        shell.selectedTab = .adminInbox
        shell.adaptToSidebarSections(showing: false)
        #expect(shell.selectedTab == .librarySection(.books))
        #expect(shell.path(for: .librarySection(.books)).count == 1)
    }

    @Test func hidingTheSidebarElsewhereLeavesTheSelectionAlone() {
        let shell = MainShellModel()
        shell.adaptToLayout(usesSidebar: true)
        shell.selectedTab = .discover
        shell.adaptToSidebarSections(showing: false)
        #expect(shell.selectedTab == .discover)
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

    @Test func theLeaderboardLandingOpensDiscoverAndCountsTheRequest() {
        let shell = MainShellModel()
        shell.isPlayerPresented = true
        shell.landOnLeaderboard()
        #expect(shell.selectedTab == .discover)
        #expect(shell.leaderboardLandingRequest == 1)
        #expect(shell.isPlayerPresented == false)
    }

    @Test func newestFirstOpensTheBooksSectionInTheTabBar() {
        let shell = MainShellModel()
        shell.librarySection = .series
        shell.openLibraryNewestFirst()
        #expect(shell.selectedTab == .library)
        #expect(shell.librarySection == .books)
        #expect(shell.libraryNewestRequest == 1)
    }

    @Test func newestFirstOpensTheBooksEntryInTheSidebar() {
        let shell = MainShellModel()
        shell.adaptToLayout(usesSidebar: true)
        shell.openLibraryNewestFirst()
        #expect(shell.selectedTab == .librarySection(.books))
    }

    @Test func inProgressOpensTheBooksSectionAndCountsTheRequest() {
        let shell = MainShellModel()
        shell.isPlayerPresented = true
        shell.librarySection = .series
        shell.openLibraryInProgress()
        #expect(shell.selectedTab == .library)
        #expect(shell.librarySection == .books)
        #expect(shell.libraryInProgressRequest == 1)
        #expect(shell.isPlayerPresented == false)
    }

    @Test func inProgressOpensTheBooksEntryInTheSidebar() {
        let shell = MainShellModel()
        shell.adaptToLayout(usesSidebar: true)
        shell.openLibraryInProgress()
        #expect(shell.selectedTab == .librarySection(.books))
    }

    /// Scene storage saved before a book could land on its readers still decodes, at the top.
    @Test func aBookDestinationDecodesWithoutAFocus() throws {
        let decoded = try JSONDecoder().decode(BookDestination.self, from: Data(#"{"id":"wok"}"#.utf8))
        #expect(decoded == BookDestination(id: "wok"))
        #expect(decoded.focus == nil)
    }

    @Test func aBookDestinationWithItsReadersInViewRoundTrips() throws {
        let destination = BookDestination(id: "wok", focus: .readers)
        let decoded = try JSONDecoder().decode(BookDestination.self, from: JSONEncoder().encode(destination))
        #expect(decoded == destination)
    }

    @Test func unreadableSceneStateLeavesTheDefaults() {
        let shell = MainShellModel()
        shell.restore(from: Data("not a snapshot".utf8))
        #expect(shell.selectedTab == .home)
        #expect(shell.path(for: .home).isEmpty)
    }

    // MARK: - The Playback menu's player items

    @Test func showNowPlayingPresentsThePlayer() {
        let shell = MainShellModel()
        shell.showPlayer()
        #expect(shell.isPlayerPresented)
        #expect(!shell.isChapterListRequested)
    }

    @Test func showChaptersPresentsThePlayerAndAsksForItsChapters() {
        let shell = MainShellModel()
        shell.showPlayerChapters()
        #expect(shell.isPlayerPresented)
        #expect(shell.isChapterListRequested)
    }

    @Test func closePlayerDismissesIt() {
        let shell = MainShellModel()
        shell.isPlayerPresented = true
        shell.closePlayer()
        #expect(!shell.isPlayerPresented)
    }
}
