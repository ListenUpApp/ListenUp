import Foundation
import Testing
@testable import ListenUp

/// The queueing contract.
///
/// Timing is not asserted here — a test that sleeps four seconds to prove a four-second timer is a
/// slow test proving a constant equals itself. What matters is that a second message never silently
/// replaces a first.
@Suite("AppMessageCenter")
@MainActor
struct AppMessageCenterTests {
    @Test func postShowsTheFirstMessageImmediately() {
        let center = AppMessageCenter()
        center.post(.info("8 books updated"))
        #expect(center.current?.text == "8 books updated")
    }

    /// The bug this prevents: two bulk actions in a row, and the user is told about the second while
    /// the first vanishes unread.
    @Test func aSecondMessageWaitsRatherThanReplacingTheFirst() {
        let center = AppMessageCenter()
        center.post(.info("first"))
        center.post(.info("second"))
        #expect(center.current?.text == "first")
    }

    @Test func dismissingAdvancesToTheWaitingMessage() {
        let center = AppMessageCenter()
        center.post(.info("first"))
        center.post(.info("second"))
        center.dismissCurrent()
        #expect(center.current?.text == "second")
    }

    @Test func dismissingTheLastMessageLeavesNothingShowing() {
        let center = AppMessageCenter()
        center.post(.info("only"))
        center.dismissCurrent()
        #expect(center.current == nil)
    }

    /// Bounded, and it drops the oldest *waiting* message — the newest is what the user just caused.
    @Test func theQueueDropsTheOldestWaitingMessageWhenItOverflows() {
        let center = AppMessageCenter()
        center.post(.info("showing"))
        for index in 0...AppMessageCenter.maxQueued {
            center.post(.info("queued \(index)"))
        }
        center.dismissCurrent()
        #expect(center.current?.text == "queued 1")
    }

    @Test func errorAndInfoCarryTheirKind() {
        #expect(AppMessage.error("nope.").kind == .error)
        #expect(AppMessage.info("yep").kind == .info)
    }
}
