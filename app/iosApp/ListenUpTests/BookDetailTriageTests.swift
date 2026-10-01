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

    /// Spec §8 and §10: Release, Edit, Match and Edit chapters — and nothing else.
    @Test func aHeldBookOffersExactlyTheFourTriageActions() {
        #expect(BookDetailLayout.forBook(isHeld: true).triageActions == [.release, .edit, .match, .editChapters])
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
