import Foundation
import os

/// Wait for an operation that reports through a completion handler, for at most `timeout`.
///
/// Returns the value the operation completed with, or `nil` when it had not completed in time. Some AVFoundation
/// calls (a zero-tolerance seek on a streaming item) can leave their completion pending forever, and awaiting one
/// directly would wait with them. A task group cannot bound it either, because it waits for every child, the stuck one
/// included. Whichever comes first, the completion or the timeout, resumes the caller exactly once; the other is
/// ignored. `start` runs synchronously on the caller's actor (the function is `nonisolated(nonsending)`, the project
/// default), so it may touch the caller's isolated state.
nonisolated func awaitCompletion<Value: Sendable>(
    within timeout: Duration,
    _ start: (@escaping @Sendable (Value) -> Void) -> Void
) async -> Value? {
    await withCheckedContinuation { (continuation: CheckedContinuation<Value?, Never>) in
        let pending = OSAllocatedUnfairLock<CheckedContinuation<Value?, Never>?>(initialState: continuation)
        let resume: @Sendable (Value?) -> Void = { value in
            let waiting = pending.withLock { slot in
                defer { slot = nil }
                return slot
            }
            waiting?.resume(returning: value)
        }
        // The timeout runs on a dispatch queue, not as a sleeping Task: a Task needs a free thread in the
        // cooperative pool to wake, and when that pool is busy the bound would stretch with it — the very wait
        // this function exists to cap. It is never cancelled: `resume` lets only the first caller through, so a
        // timer that fires after the completion is a no-op.
        DispatchQueue.global().asyncAfter(deadline: .now() + timeout.timeInterval) { resume(nil) }
        start { value in resume(value) }
    }
}

nonisolated private extension Duration {
    /// This duration in seconds, for `DispatchTime` arithmetic.
    var timeInterval: TimeInterval {
        let (seconds, attoseconds) = components
        return TimeInterval(seconds) + TimeInterval(attoseconds) / 1e18
    }
}
