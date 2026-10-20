import SwiftUI
import Testing
import UIKit
@testable import ListenUp

/// A held book's detail page is triage-only (spec §8): the held card and the toolbar's Held pill, nothing else.
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

    /// Spec §3.2.3, as the held card draws it: Release prominent, then Edit Details, Match Details and Edit Chapters —
    /// the held book's actions all in one place (Edit left the toolbar for the card).
    @Test func theHeldSectionDrawsReleaseProminentAndTheThreeFixes() {
        let arrangement = BookDetailHeldSection.arrange(BookDetailLayout.forBook(isHeld: true).triageActions)
        #expect(arrangement.prominent == .release)
        #expect(arrangement.secondary == [.edit, .match, .editChapters])
    }

    @Test func theHeldWordsAreTheBoards() {
        #expect(BookDetailHeldSection.message
            == "Hidden from every member, and it can’t be played until you release it.")
        #expect(BookDetailHeldSection.title(of: .release) == "Release to Members")
        #expect(BookDetailHeldSection.title(of: .edit) == "Edit Details")
        #expect(BookDetailHeldSection.title(of: .match) == "Match Details…")
        #expect(BookDetailHeldSection.title(of: .editChapters) == "Edit Chapters")
    }

    /// Release to Members is amber on the card in every appearance, at body-text contrast.
    @Test func releaseToMembersReadsOnTheCard() {
        for traits in UITraitCollection.colorAppearances {
            let amber = SRGBColor(UIColor(Color.luWarning), resolvedFor: traits)
            let card = SRGBColor(UIColor.secondarySystemBackground, resolvedFor: traits)
            #expect(amber.contrastRatio(against: card) >= ContrastMinimum.text, "\(traits)")
        }
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

    /// A held book has no More menu: the toolbar shows the Held pill, and its actions are the held card's.
    @Test func aHeldBookShowsTheHeldPillInTheToolbar() {
        #expect(BookDetailLayout.toolbar(for: .triage) == .heldPill)
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
