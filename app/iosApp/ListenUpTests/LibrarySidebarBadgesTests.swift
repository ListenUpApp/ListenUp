import Testing
@testable import ListenUp

/// The iPad sidebar's badges: a count on every section while the sidebar lists them, the held count on Server › Inbox;
/// only the held count, on the collapsed Library item, when it hides.
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
