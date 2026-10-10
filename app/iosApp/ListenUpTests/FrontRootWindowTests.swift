import Foundation
import Testing
@testable import ListenUp

/// Which window's root presents what the whole app shares — the invite sheet and the error alert.
@MainActor
@Suite("FrontRootWindow")
struct FrontRootWindowTests {

    @Test func theFrontWindowIsTheLastOneToBecomeActive() {
        let tracker = FrontRootWindow()
        let front = UUID()
        let back = UUID()
        tracker.became(back)
        tracker.became(front)

        #expect(tracker.isFront(front))
        #expect(!tracker.isFront(back))
    }

    /// A cold launch from a link must not present nothing.
    @Test func beforeAnyWindowReportsTheFirstToAskIsFront() {
        #expect(FrontRootWindow().isFront(UUID()))
    }

    @Test func aClosedFrontWindowGivesUpItsPlace() {
        let tracker = FrontRootWindow()
        let closed = UUID()
        tracker.became(closed)
        tracker.wentAway(closed)

        #expect(tracker.isFront(UUID()))
    }

    @Test func aBackWindowClosingChangesNothing() {
        let tracker = FrontRootWindow()
        let front = UUID()
        let back = UUID()
        tracker.became(back)
        tracker.became(front)
        tracker.wentAway(back)

        #expect(tracker.isFront(front))
        #expect(!tracker.isFront(back))
    }
}
