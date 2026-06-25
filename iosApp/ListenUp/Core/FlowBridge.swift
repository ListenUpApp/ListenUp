import Foundation
import Shared

/// Canonical bridge from a Kotlin flow into `@Observable` Swift state.
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
    ///
    /// No `Sendable` constraints: `FlowBridge` is `@MainActor`, `bind` is called on
    /// the main actor, and the collecting task is `@MainActor`-isolated — the
    /// (non-`Sendable`) bridged Kotlin flow never crosses an isolation boundary.
    func bind<S: AsyncSequence>(
        _ sequence: S,
        to sink: @escaping @MainActor (S.Element) -> Void
    ) {
        let task = Task { @MainActor in
            // `for await` keeps the iterator confined to this `@MainActor` task —
            // the manual `iterator.next()` form `sending`s the non-Sendable bridged
            // iterator under Swift 6 strict concurrency. A thrown error from a
            // failing Kotlin flow ends the subscription (treated as end-of-sequence).
            do {
                for try await value in sequence {
                    if Task.isCancelled { break }
                    sink(value)
                }
            } catch {
                // Kotlin flow failed — end the subscription.
            }
        }
        tasks.append(task)
    }

    /// Bind a Swift Export `KotlinTypedFlow` (Kotlin `Flow`/`StateFlow`). It is not itself an
    /// `AsyncSequence`, but vends one via `asAsyncSequence()`; this overload lets observers keep the
    /// same `bind(flow) { … }` call shape.
    func bind<E>(
        _ flow: any KotlinTypedFlow<E>,
        to sink: @escaping @MainActor (E) -> Void
    ) {
        bind(flow.asAsyncSequence(), to: sink)
    }

    /// Cancel every subscription. Call from the owning observer's teardown.
    func cancelAll() {
        for task in tasks { task.cancel() }
        tasks.removeAll()
    }
}
