import SwiftUI
import Testing
@testable import ListenUp

@Suite("BookDetailObserver pure helpers")
struct BookDetailObserverTintTests {
    @Test("mark-finished defaults started to now when unknown")
    func startedDefaultsToNow() {
        let ts = BookDetailObserver.markCompleteTimestamps(startedAtMs: nil, now: 1000)
        #expect(ts.start == 1000)
        #expect(ts.finish == 1000)
    }

    @Test("mark-finished preserves a known started timestamp")
    func startedPreserved() {
        let ts = BookDetailObserver.markCompleteTimestamps(startedAtMs: 500, now: 1000)
        #expect(ts.start == 500)
        #expect(ts.finish == 1000)
    }
}
