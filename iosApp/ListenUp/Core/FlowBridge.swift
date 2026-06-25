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
    /// If the bridged Kotlin flow *fails* mid-stream (an RPC/firehose drop, a
    /// deserialization fault, an in-flight `RpcEvent.Error`), the error is always
    /// logged and forwarded to `onError` — never swallowed. Normal completion and
    /// cancellation do not invoke `onError`.
    ///
    /// No `Sendable` constraints: `FlowBridge` is `@MainActor`, `bind` is called on
    /// the main actor, and the collecting task is `@MainActor`-isolated — the
    /// (non-`Sendable`) bridged Kotlin flow never crosses an isolation boundary.
    func bind<S: AsyncSequence>(
        _ sequence: S,
        onError: (@MainActor (Error) -> Void)? = nil,
        to sink: @escaping @MainActor (S.Element) -> Void
    ) {
        let task = Task { @MainActor in
            // `for await` keeps the iterator confined to this `@MainActor` task —
            // the manual `iterator.next()` form `sending`s the non-Sendable bridged
            // iterator under Swift 6 strict concurrency.
            do {
                for try await value in sequence {
                    if Task.isCancelled { break }
                    sink(value)
                }
            } catch is CancellationError {
                // Normal teardown — not a failure.
            } catch {
                // A failing Kotlin flow must never die silently: log it (for field
                // diagnosis) and surface it to the observer's optional `onError`.
                Log.error("FlowBridge subscription failed", error: error)
                onError?(error)
            }
        }
        tasks.append(task)
    }

    /// Bind a Swift Export `KotlinTypedFlow` (Kotlin `Flow`/`StateFlow`). It is not itself an
    /// `AsyncSequence`, but vends one via `asAsyncSequence()`; this overload lets observers keep the
    /// same `bind(flow) { … }` call shape.
    func bind<E>(
        _ flow: any KotlinTypedFlow<E>,
        onError: (@MainActor (Error) -> Void)? = nil,
        to sink: @escaping @MainActor (E) -> Void
    ) {
        bind(flow.asAsyncSequence(), onError: onError, to: sink)
    }

    /// Cancel every subscription. Call from the owning observer's teardown.
    func cancelAll() {
        for task in tasks { task.cancel() }
        tasks.removeAll()
    }
}
