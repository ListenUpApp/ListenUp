import Testing
@testable import iosApp

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

@MainActor
@Suite("FlowBridge")
struct FlowBridgeTests {
    @Test func deliversEveryValueToTheSink() async {
        let bridge = FlowBridge()
        var received: [Int] = []
        bridge.bind(NumberSequence(values: [1, 2, 3])) { received.append($0) }
        // Yield until the finite sequence drains.
        try? await Task.sleep(for: .milliseconds(50))
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
        bridge.bind(NumberSequence(values: [1, 2])) { a.append($0) }
        bridge.bind(NumberSequence(values: [9, 8])) { b.append($0) }
        try? await Task.sleep(for: .milliseconds(50))
        #expect(a == [1, 2])
        #expect(b == [9, 8])
    }
}
