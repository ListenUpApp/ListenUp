import Foundation
import Testing
@testable import ListenUp

@Suite("Restricted-book lock")
struct RestrictedMarkerTests {
    @Test func sitsInTheCornerAndStepsClearOfTheSelectionCircle() {
        #expect(RestrictedMarker.leadingOffset(isSelecting: false) == 0)
        #expect(RestrictedMarker.leadingOffset(isSelecting: true) == 36)
    }

    @Test func aRestrictedCardsVoiceOverLabelEndsWithWhy() {
        #expect(
            RestrictedMarker.label("Dune, Frank Herbert", isRestricted: true)
                == "Dune, Frank Herbert. In a collection, so only people it is shared with can see it."
        )
    }

    @Test func anyOtherCardsLabelIsUnchanged() {
        #expect(RestrictedMarker.label("Dune, Frank Herbert", isRestricted: false) == "Dune, Frank Herbert")
    }
}
