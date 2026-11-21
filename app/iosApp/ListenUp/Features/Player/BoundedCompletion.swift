import Foundation
import os

/// Wait for an operation that reports through a completion handler, for at most `timeout`.
///
/// Returns the value the operation completed with, or `nil` when it had not completed in time. Some AVFoundation
/// calls (a zero-tolerance seek on a streaming item) can leave their completion pending forever, and awaiting one
/// directly would wait with them. A task group cannot bound it either, because it waits for every child, the stuck one
/// included. Whichever comes first, the completion or the timeout, resumes the caller exactly once; the other is
/// ignored. `start` runs synchronously on the caller's actor, so it may touch the caller's isolated state.
func awaitCompletion<Value: Sendable>(
    within timeout: Duration,
    isolation: isolated (any Actor)? = #isolation,
    _ start: (@escaping @Sendable (Value) -> Void) -> Void
) async -> Value? {
    await withCheckedContinuation(isolation: isolation) { (continuation: CheckedContinuation<Value?, Never>) in
        let pending = OSAllocatedUnfairLock<CheckedContinuation<Value?, Never>?>(initialState: continuation)
        let resume: @Sendable (Value?) -> Void = { value in
            let waiting = pending.withLock { slot in
                defer { slot = nil }
                return slot
            }
            waiting?.resume(returning: value)
        }
        let timer = Task {
            try? await Task.sleep(for: timeout)
            resume(nil)
        }
        start { value in
            timer.cancel()
            resume(value)
        }
    }
}
