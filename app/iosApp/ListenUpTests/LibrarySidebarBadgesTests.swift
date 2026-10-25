import Testing
@testable import ListenUp

/// The iPad sidebar's badges: a count on every section while the sidebar lists them, the held count on Server › Inbox;
/// only the held count, on the collapsed Library item, when it hides.
@Suite("Sidebar sections")
struct SidebarSectionsTests {
    @Test func theTabBarSayingSoIsEnough() {
        #expect(SidebarSections.areListed(tabBarSaysShowing: true, usesSidebar: false, placesSidebarFirst: false))
    }

    /// SwiftUI's `isTabBarShowingSections` answers false on the iOS 27 sidebar-first placement even while the sidebar is
    /// on screen (iPhone Duo, open, iOS 27.1: `tabBarPlacement` reports `bottomBar`), so the shell also reads it from
    /// its own regular-by-regular decision.
    @Test func theOpenDuoListsSectionsEvenWhenTheEnvironmentSaysNo() {
        #expect(SidebarSections.areListed(tabBarSaysShowing: false, usesSidebar: true, placesSidebarFirst: true))
    }

    @Test func aPhoneKeepsTheTabBarsAnswer() {
        #expect(!SidebarSections.areListed(tabBarSaysShowing: false, usesSidebar: false, placesSidebarFirst: true))
    }

    @Test func beforeSidebarFirstPlacementTheTabBarsAnswerStands() {
        #expect(!SidebarSections.areListed(tabBarSaysShowing: false, usesSidebar: true, placesSidebarFirst: false))
    }
}

@Suite("Library sidebar badges")
struct LibrarySidebarBadgesTests {
    let counts = LibrarySectionCountsValue(books: 248, series: 42, authors: 132, narrators: 97)

    @Test func sidebarShownCountsEverySectionAndHoldsTheHeldCountForInbox() {
        let badges = LibrarySidebarBadges.forPlacement(counts: counts, heldCount: 3, showingSections: true)
        #expect(badges.section == 0)
        #expect(badges.entry(.books) == 248 && badges.entry(.series) == 42)
        #expect(badges.entry(.authors) == 132 && badges.entry(.narrators) == 97)
        #expect(badges.inbox == 3)
    }

    @Test func sidebarHiddenPutsOnlyTheHeldCountOnTheCollapsedLibraryItem() {
        let badges = LibrarySidebarBadges.forPlacement(counts: counts, heldCount: 3, showingSections: false)
        #expect(badges.section == 3)
        #expect(badges.entry(.books) == 0)
        #expect(badges.inbox == 0)
    }

    @Test(arguments: [true, false])
    func nothingHeldBadgesNoInbox(showingSections: Bool) {
        let badges = LibrarySidebarBadges.forPlacement(counts: counts, heldCount: 0, showingSections: showingSections)
        #expect(badges.section == 0)
        #expect(badges.inbox == 0)
    }
}

/// Where an admin reaches the inbox on iPad (HIG, Tab bars: a badge sits on the tab whose section holds the news; the
/// sidebar carries what the tab bar has no room for — https://developer.apple.com/design/human-interface-guidelines/tab-bars).
@Suite("Inbox placement")
struct InboxPlacementTests {
    @Test func theServerSectionIsSidebarContentForAnAdmin() {
        #expect(InboxPlacement.showsServerSection(isAdmin: true, showingSections: true))
        // A hidden sidebar collapses the section into the floating tab bar: no stray "Server" tab there.
        #expect(!InboxPlacement.showsServerSection(isAdmin: true, showingSections: false))
        #expect(!InboxPlacement.showsServerSection(isAdmin: false, showingSections: true))
    }

    @Test func theBooksGridLeadsToTheInboxWheneverTheSidebarDoesNot() {
        // The compact Library tab.
        #expect(InboxPlacement.showsGridEntry(showsSectionPicker: true, showingSections: false, isSelecting: false))
        // An iPad section entry with the sidebar hidden: the Library tab (badged) must still reach the inbox.
        #expect(InboxPlacement.showsGridEntry(showsSectionPicker: false, showingSections: false, isSelecting: false))
        // The sidebar shows Server › Inbox: one way in, not two.
        #expect(!InboxPlacement.showsGridEntry(showsSectionPicker: false, showingSections: true, isSelecting: false))
        #expect(!InboxPlacement.showsGridEntry(showsSectionPicker: true, showingSections: false, isSelecting: true))
    }

    @Test func theBadgeIsOnlyOnTheTabThatLeadsToTheHeldBooks() {
        let counts = LibrarySectionCountsValue(books: 15, series: 1, authors: 14, narrators: 15)
        let collapsed = LibrarySidebarBadges.forPlacement(counts: counts, heldCount: 3, showingSections: false)
        #expect(collapsed.section == 3 && collapsed.inbox == 0)
        let shown = LibrarySidebarBadges.forPlacement(counts: counts, heldCount: 3, showingSections: true)
        #expect(shown.section == 0 && shown.inbox == 3)
    }
}

