import Foundation
import Testing
@testable import ListenUp

/// `awaitCompletion(within:)` is what keeps `AudioEngine.load` from waiting forever on a resume seek AVFoundation
/// never completes. These pin its contract with a stand-in for the seek, since no live `AVPlayer` can be made to
/// hang on demand.
@Suite("Bounded completion")
struct BoundedCompletionTests {
    /// The bug itself: an operation that never calls back must not hold the caller forever. Raced against a 5 s
    /// bound of its own, so a regression fails here as `.hung` instead of hanging the suite.
    @Test func anOperationThatNeverCompletesGivesUpAtTheTimeout() async {
        let outcome: Outcome = await withTaskGroup(of: Outcome.self) { group in
            group.addTask {
                let value: Bool? = await awaitCompletion(within: .milliseconds(50)) { _ in }
                return value == nil ? .timedOut : .completed
            }
            group.addTask { try? await Task.sleep(for: .seconds(5)); return .hung }
            let first = await group.next() ?? .hung
            group.cancelAll()
            return first
        }

        #expect(outcome == .timedOut)
    }

    /// An operation that does complete hands its value back, well inside the bound.
    @Test func anOperationThatCompletesReturnsItsValue() async {
        let value: Bool? = await awaitCompletion(within: .seconds(5)) { done in
            DispatchQueue.global().async { done(true) }
        }

        #expect(value == true)
    }

    /// A completion that arrives after the timeout already resumed the caller is ignored, never a second resume
    /// (which would trap): a seek that lands late must not crash the engine that gave up on it.
    @Test func aCompletionAfterTheTimeoutIsIgnored() async {
        let late = LateCallback()
        let value: Bool? = await awaitCompletion(within: .milliseconds(20)) { done in late.store(done) }
        late.fire(true)

        #expect(value == nil)
    }

    private enum Outcome { case completed, timedOut, hung }
}

/// Holds a completion so a test can call it after the wait it belongs to has ended.
private final class LateCallback: @unchecked Sendable {
    private let lock = NSLock()
    private var callback: (@Sendable (Bool) -> Void)?

    func store(_ callback: @escaping @Sendable (Bool) -> Void) {
        lock.withLock { self.callback = callback }
    }

    func fire(_ value: Bool) {
        lock.withLock { callback }?(value)
    }
}
