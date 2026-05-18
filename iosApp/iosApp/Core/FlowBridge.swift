import Foundation

/// Canonical bridge from a Kotlin flow (SKIE-exposed as an `AsyncSequence`) into
/// `@Observable` Swift state.
///
/// An observer owns one `FlowBridge`, registers `(flow → sink)` bindings in its
/// initializer, and the bridge manages each subscription as a structured-concurrency
/// task — collecting on the main actor and cancelling them all together on teardown.
/// Observers never hand-roll `Task { for await … }` loops.
@MainActor
final class FlowBridge {
    private var tasks: [Task<Void, Never>] = []

    init() {}

    /// Subscribe `sequence`; deliver each element to `sink` on the main actor until
    /// the sequence ends or `cancelAll()` is called.
    func bind<S: AsyncSequence & Sendable>(
        _ sequence: S,
        to sink: @escaping @MainActor (S.Element) -> Void
    ) where S.Element: Sendable {
        let task = Task { @MainActor in
            var iterator = sequence.makeAsyncIterator()
            while !Task.isCancelled {
                // `try?` also catches a thrown error (a Kotlin flow that fails),
                // treating it like end-of-sequence. The StateFlow-backed flows this
                // bridge serves never throw; the error-handling policy is to be
                // settled at the green-build pass, once SKIE's throwing behaviour
                // for bridged flows is observable.
                guard let value = try? await iterator.next() else { break }
                if Task.isCancelled { break }
                sink(value)
            }
        }
        tasks.append(task)
    }

    /// Cancel every subscription. Call from the owning observer's teardown.
    func cancelAll() {
        for task in tasks { task.cancel() }
        tasks.removeAll()
    }
}
