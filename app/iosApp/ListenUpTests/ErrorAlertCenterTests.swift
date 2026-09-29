import Foundation
import Testing
@testable import ListenUp

/// The queueing contract behind the app-level error alert.
///
/// An alert is modal — one at a time — so the question this pins is what happens to the second
/// failure while the first is still on screen: it waits, it is never silently swallowed, and the
/// same sentence is not said twice in a row.
@Suite("ErrorAlertCenter")
@MainActor
struct ErrorAlertCenterTests {
    @Test func postShowsTheFirstErrorImmediately() {
        let center = ErrorAlertCenter()
        center.post("Couldn't add to shelf.")
        #expect(center.current?.message == "Couldn't add to shelf.")
    }

    /// The bug this prevents: two failures in a row, and the user is told about the second while
    /// the first vanishes unread.
    @Test func aSecondErrorWaitsRatherThanReplacingTheFirst() {
        let center = ErrorAlertCenter()
        center.post("first.")
        center.post("second.")
        #expect(center.current?.message == "first.")
    }

    /// Two steps, so the first alert fully leaves before the second is presented.
    @Test func dismissingFreesTheSlotAndShowNextPresentsTheWaitingError() {
        let center = ErrorAlertCenter()
        center.post("first.")
        center.post("second.")
        center.dismissCurrent()
        #expect(center.current == nil)
        #expect(center.hasWaiting)
        center.showNext()
        #expect(center.current?.message == "second.")
        #expect(!center.hasWaiting)
    }

    /// A failure arriving between a dismissal and the next alert waits its turn behind it.
    @Test func anErrorPostedMidDismissalQueuesBehindTheWaitingOne() {
        let center = ErrorAlertCenter()
        center.post("first.")
        center.post("second.")
        center.dismissCurrent()
        center.post("third.")
        center.showNext()
        #expect(center.current?.message == "second.")
    }

    @Test func dismissingTheLastErrorLeavesNothingShowing() {
        let center = ErrorAlertCenter()
        center.post("only.")
        center.dismissCurrent()
        #expect(center.current == nil)
    }

    /// A failure that repeats (a retry that fails the same way) is one alert, not a stack of
    /// identical ones the user has to dismiss one by one.
    @Test func theSameSentenceIsNotQueuedTwice() {
        let center = ErrorAlertCenter()
        center.post("same.")
        center.post("same.")
        center.post("other.")
        center.post("other.")
        center.dismissCurrent()
        center.showNext()
        #expect(center.current?.message == "other.")
        center.dismissCurrent()
        center.showNext()
        #expect(center.current == nil)
    }

    /// Bounded, and it drops the oldest *waiting* error — the newest is what the user just caused.
    @Test func theQueueDropsTheOldestWaitingErrorWhenItOverflows() {
        let center = ErrorAlertCenter()
        center.post("showing.")
        for index in 0...ErrorAlertCenter.maxQueued {
            center.post("queued \(index).")
        }
        center.dismissCurrent()
        center.showNext()
        #expect(center.current?.message == "queued 1.")
    }

    /// Each presentation is a distinct identity, so SwiftUI re-presents the alert for a repeat of
    /// an earlier sentence rather than treating it as the alert already dismissed.
    @Test func aRepeatAfterDismissalIsANewAlert() {
        let center = ErrorAlertCenter()
        center.post("again.")
        let first = center.current?.id
        center.dismissCurrent()
        center.post("again.")
        #expect(center.current?.message == "again.")
        #expect(center.current?.id != first)
    }
}
