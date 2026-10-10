import Testing
@testable import ListenUp

/// An edit sheet never throws away changes without asking (HIG, Sheets: "if people have unsaved
/// changes in the sheet when they begin swiping to dismiss it, use an action sheet to let them
/// confirm their action").
@Suite("Edit sheet dismissal")
struct EditSheetDismissalTests {
    @Test func cancelWithNoChangesClosesTheSheet() {
        #expect(EditSheetDismissal(hasChanges: false).onCancel == .dismiss)
    }

    @Test func cancelWithChangesAsksBeforeDiscarding() {
        #expect(EditSheetDismissal(hasChanges: true).onCancel == .confirmDiscard)
    }

    @Test func swipeDownIsHeldOnlyWhileThereAreChanges() {
        #expect(EditSheetDismissal(hasChanges: true).blocksInteractiveDismiss)
        #expect(!EditSheetDismissal(hasChanges: false).blocksInteractiveDismiss)
    }

    @Test func aRatingIsAChangeOnlyWhenItDiffersFromTheOneTheSheetOpenedOn() {
        #expect(!RateBookSheet.hasChanges(halfStars: 0, note: "", openedOn: nil))
        #expect(RateBookSheet.hasChanges(halfStars: 6, note: "", openedOn: nil))
        #expect(!RateBookSheet.hasChanges(halfStars: 6, note: "Good", openedOn: MyRating(halfStars: 6, note: "Good")))
        #expect(RateBookSheet.hasChanges(halfStars: 6, note: "Great", openedOn: MyRating(halfStars: 6, note: "Good")))
    }

    @Test func aShelfDraftIsAChangeOnlyWhenItDiffersFromWhatItOpenedOn() {
        let empty = ShelfDraft(name: "", description: "", isPrivate: false)
        #expect(!empty.differs(from: empty))
        #expect(ShelfDraft(name: "Sci-fi", description: "", isPrivate: false).differs(from: empty))
        #expect(ShelfDraft(name: "Sci-fi", description: "", isPrivate: true)
            .differs(from: ShelfDraft(name: "Sci-fi", description: "", isPrivate: false)))
    }
}
