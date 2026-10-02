import Foundation
import Testing
@testable import ListenUp

@Suite("Restricted-book lock")
struct RestrictedMarkerTests {
    @Test func sitsInTheCornerAndStepsClearOfTheSelectionCircle() {
        #expect(RestrictedMarker.leadingOffset(isSelecting: false) == 0)
        #expect(RestrictedMarker.leadingOffset(isSelecting: true) == 36)
    }

    @Test func theLockShowsOnlyOnARestrictedBookThatIsNotHeld() {
        #expect(RestrictedMarker.shows(isHeld: false, isRestricted: true))
        #expect(!RestrictedMarker.shows(isHeld: false, isRestricted: false))
        // Held wins the corner: the inbox's Held badge owns a held book's cover.
        #expect(!RestrictedMarker.shows(isHeld: true, isRestricted: true))
        #expect(!RestrictedMarker.shows(isHeld: true, isRestricted: false))
    }

    @Test func aRestrictedCardsVoiceOverLabelEndsWithWhy() {
        #expect(
            RestrictedMarker.label("Dune, Frank Herbert", isRestricted: true)
                == "Dune, Frank Herbert. In a collection, so only people it is shared with can see it."
        )
    }

    @Test func aSearchBookHitReadsItsTitleBeforeItsBadge() {
        func hit(isHeld: Bool) -> SearchRow {
            SearchRow(
                id: "b1", kind: .book, name: "Dune", subtitle: nil, author: "Frank Herbert",
                coverPath: nil, coverHash: nil, isHeld: isHeld
            )
        }
        #expect(
            hit(isHeld: false).bookAccessibilityLabel(byline: "Frank Herbert", isRestricted: true)
                == "Dune, Frank Herbert. In a collection, so only people it is shared with can see it."
        )
        #expect(hit(isHeld: false).bookAccessibilityLabel(byline: nil, isRestricted: false) == "Dune")
        // Held owns the corner, so a held hit names the hold, never the lock.
        #expect(
            hit(isHeld: true).bookAccessibilityLabel(byline: "Frank Herbert", isRestricted: true)
                == "Dune, Frank Herbert. Held for review, hidden from all members"
        )
    }

    @Test func anyOtherCardsLabelIsUnchanged() {
        #expect(RestrictedMarker.label("Dune, Frank Herbert", isRestricted: false) == "Dune, Frank Herbert")
    }
}
