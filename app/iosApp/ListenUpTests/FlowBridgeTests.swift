import Testing
@testable import ListenUp

/// A simple `Sendable` async sequence for exercising `FlowBridge` without KMP.
private struct NumberSequence: AsyncSequence, Sendable {
    let values: [Int]
    struct Iterator: AsyncIteratorProtocol {
        var remaining: [Int]
        mutating func next() async -> Int? {
            guard !remaining.isEmpty else { return nil }
            // Sleep between elements so cancellation has a real window to interrupt.
            try? await Task.sleep(for: .milliseconds(10))
            return remaining.removeFirst()
        }
    }
    func makeAsyncIterator() -> Iterator { Iterator(remaining: values) }
}

/// A distinctive error type so the `onError` assertion can prove the *thrown*
/// error is the one forwarded, not some incidental cancellation.
private struct BridgeTestError: Error, Equatable {}

/// Emits `values`, then throws `BridgeTestError` — mimics a Kotlin flow that
/// fails mid-stream (the failure mode this plan makes observable).
private struct ThrowingSequence: AsyncSequence, Sendable {
    let values: [Int]
    struct Iterator: AsyncIteratorProtocol {
        var remaining: [Int]
        mutating func next() async throws -> Int? {
            guard !remaining.isEmpty else { throw BridgeTestError() }
            try? await Task.sleep(for: .milliseconds(10))
            return remaining.removeFirst()
        }
    }
    func makeAsyncIterator() -> Iterator { Iterator(remaining: values) }
}

@MainActor
@Suite("FlowBridge")
struct FlowBridgeTests {
    @Test func deliversEveryValueToTheSink() async {
        let bridge = FlowBridge()
        var received: [Int] = []
        // Wait until the sink has been called exactly 3 times — the sequence is done.
        // Using a continuation here is deterministic: the test cannot assert before
        // delivery completes, regardless of scheduler load. A hang here means a real
        // delivery failure, which is the honest signal we want.
        await withCheckedContinuation { continuation in
            bridge.bind(NumberSequence(values: [1, 2, 3])) { value in
                received.append(value)
                if received.count == 3 { continuation.resume() }
            }
        }
        #expect(received == [1, 2, 3])
    }

    @Test func cancelAllStopsDelivery() async {
        let bridge = FlowBridge()
        var received: [Int] = []
        bridge.bind(NumberSequence(values: [1, 2, 3])) { received.append($0) }
        bridge.cancelAll()
        try? await Task.sleep(for: .milliseconds(50))
        // Cancellation raced the first value; the invariant is that it does not
        // keep delivering after cancelAll — never the full sequence.
        #expect(received.count < 3)
    }

    @Test func supportsMultipleConcurrentBindings() async {
        let bridge = FlowBridge()
        var a: [Int] = []
        var b: [Int] = []
        // Resume once both bindings have fully drained. `doneCount` is incremented only
        // on the main actor (the struct is @MainActor), so no locking is needed.
        // `resumed` guards against the rare case both sinks hit their target in the
        // same turn, which would double-resume the continuation and crash.
        await withCheckedContinuation { continuation in
            var doneCount = 0
            var resumed = false
            let checkDone = {
                doneCount += 1
                if doneCount == 2 && !resumed {
                    resumed = true
                    continuation.resume()
                }
            }
            bridge.bind(NumberSequence(values: [1, 2])) { value in
                a.append(value)
                if a.count == 2 { checkDone() }
            }
            bridge.bind(NumberSequence(values: [9, 8])) { value in
                b.append(value)
                if b.count == 2 { checkDone() }
            }
        }
        #expect(a == [1, 2])
        #expect(b == [9, 8])
    }

    @Test func failureInvokesOnErrorWithTheThrownError() async {
        let bridge = FlowBridge()
        var received: [Int] = []
        let caught: BridgeTestError? = await withCheckedContinuation { continuation in
            bridge.bind(
                ThrowingSequence(values: [1, 2]),
                onError: { error in continuation.resume(returning: error as? BridgeTestError) }
            ) { received.append($0) }
        }
        // The thrown error reached `onError` on the main actor…
        #expect(caught == BridgeTestError())
        // …after both pre-failure values were delivered, and no `sink` ran past the throw.
        #expect(received == [1, 2])
    }

    @Test func normalCompletionDoesNotInvokeOnError() async {
        let bridge = FlowBridge()
        var received: [Int] = []
        var onErrorCalled = false
        await withCheckedContinuation { continuation in
            bridge.bind(
                NumberSequence(values: [1, 2, 3]),
                onError: { _ in onErrorCalled = true }
            ) { value in
                received.append(value)
                if received.count == 3 { continuation.resume() }
            }
        }
        // Drain any stray task turn before asserting the negative.
        try? await Task.sleep(for: .milliseconds(20))
        #expect(received == [1, 2, 3])
        #expect(!onErrorCalled)
    }

    @Test func cancellationDoesNotInvokeOnError() async {
        let bridge = FlowBridge()
        var onErrorCalled = false
        bridge.bind(NumberSequence(values: [1, 2, 3]), onError: { _ in onErrorCalled = true }) { _ in }
        bridge.cancelAll()
        try? await Task.sleep(for: .milliseconds(50))
        // Cancellation is normal teardown, not a failure — `onError` must stay silent.
        #expect(!onErrorCalled)
    }

    /// The crux: `cancelAll()` is `nonisolated`, so calling it off the main actor must not
    /// trap — this is the regression guard for the old `MainActor.assumeIsolated { … }`
    /// teardown, which crashed when an observer's `deinit` ran off the main thread.
    @Test func cancelAllIsSafeFromOffTheMainActor() async {
        let bridge = FlowBridge()
        var received: [Int] = []
        bridge.bind(NumberSequence(values: [1, 2, 3])) { received.append($0) }
        // Cancel from a detached task — i.e. NOT on the main actor. The old
        // `assumeIsolated` path would trap here; `nonisolated cancelAll()` must not.
        await Task.detached { bridge.cancelAll() }.value
        try? await Task.sleep(for: .milliseconds(50))
        // No crash, and delivery stopped — the cancellation took effect.
        #expect(received.count < 3)
    }
}
