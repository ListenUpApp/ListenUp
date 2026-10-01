import Testing
@testable import ListenUp

/// A held book's detail page is triage-only (spec §8): the held section and Edit, nothing else.
@Suite("Book detail triage layout")
struct BookDetailTriageTests {
    @Test func aHeldBookGetsTheTriageLayout() {
        let layout = BookDetailLayout.forBook(isHeld: true)
        #expect(layout == .triage)
        #expect(layout.showsHeldSection)
        // Absent: Play and Download (resume bar), shelf and mark-finished (pills), rating, readers,
        // Hardcover, and the overflow menu's shelf, collection, share, resets and delete.
        #expect(layout.showsPlayback == false)
        #expect(layout.showsSocial == false)
        #expect(layout.showsOverflowMenu == false)
    }

    /// Spec §8 and §10, as the held section draws them: Release prominent, Match and Edit chapters
    /// secondary — and nothing else. Edit is not here: it is the toolbar's (HIG, Toolbars).
    @Test func theHeldSectionDrawsReleaseProminentAndTheTwoMetadataFixes() {
        let arrangement = BookDetailHeldSection.arrange(BookDetailLayout.forBook(isHeld: true).triageActions)
        #expect(arrangement.prominent == .release)
        #expect(arrangement.secondary == [.match, .editChapters])
    }

    @Test func anOrdinaryBookDrawsNoHeldActions() {
        let arrangement = BookDetailHeldSection.arrange(BookDetailLayout.forBook(isHeld: false).triageActions)
        #expect(arrangement.prominent == nil)
        #expect(arrangement.secondary.isEmpty)
    }

    // MARK: - Toolbar

    /// Until the book has loaded nobody knows whether it is held, so the page offers no toolbar
    /// item at all — never the full menu (Delete, Share, shelf) that then swaps to Edit.
    @Test func aLoadingBookOffersNoToolbarItem() {
        #expect(BookDetailLayout.toolbar(for: nil) == .none)
    }

    @Test func aHeldBookOffersEditAloneInTheToolbar() {
        #expect(BookDetailLayout.toolbar(for: .triage) == .edit)
    }

    @Test func anOrdinaryBookOffersTheFullMenu() {
        #expect(BookDetailLayout.toolbar(for: .full) == .overflowMenu)
    }

    @Test func anOrdinaryBookGetsEverything() {
        let layout = BookDetailLayout.forBook(isHeld: false)
        #expect(layout == .full)
        #expect(layout.showsHeldSection == false)
        #expect(layout.triageActions.isEmpty)
        #expect(layout.showsPlayback)
        #expect(layout.showsSocial)
        #expect(layout.showsOverflowMenu)
    }

    @Test func releaseAsksInTheCalmWords() {
        #expect(ReleaseToEveryone.title == "Release to everyone?")
        #expect(ReleaseToEveryone.confirm == "Release")
        #expect(ReleaseToEveryone.message(count: 1) == "Every member will be able to find and play it.")
        #expect(ReleaseToEveryone.message(count: 3) == "Every member will be able to find and play them.")
    }
}
