import Testing
@testable import ListenUp

/// Where the held count sits in the iPad shell: on Books while the sidebar lists the Library
/// sections, on the collapsed Library item when the sidebar hides — never on both.
@Suite("Library badges")
struct LibraryBadgesTests {
    @Test func sidebarShownBadgesBooksOnly() {
        let badges = LibraryBadges.forPlacement(heldCount: 3, showingSections: true)
        #expect(badges == LibraryBadges(section: 0, books: 3))
    }

    @Test func sidebarHiddenBadgesTheCollapsedLibraryItem() {
        let badges = LibraryBadges.forPlacement(heldCount: 3, showingSections: false)
        #expect(badges == LibraryBadges(section: 3, books: 0))
    }

    @Test(arguments: [true, false])
    func nothingHeldBadgesNothing(showingSections: Bool) {
        let badges = LibraryBadges.forPlacement(heldCount: 0, showingSections: showingSections)
        #expect(badges == LibraryBadges(section: 0, books: 0))
    }
}
