import Foundation
import os
import Shared

/// Canonical bridge from a Kotlin flow into `@Observable` Swift state.
///
/// An observer owns one `FlowBridge`, registers `(flow → sink)` bindings in its
/// initializer, and the bridge manages each subscription as a structured-concurrency
/// task — collecting on the main actor and cancelling them all together on teardown.
/// Observers never hand-roll `Task { for await … }` loops.
///
/// Teardown is **nonisolated-safe**: `cancelAll()` can be called from any thread,
/// including a `@MainActor` class's `nonisolated deinit`. The task list is guarded by
/// an `OSAllocatedUnfairLock`, and `Task.cancel()` is itself thread-safe — so an
/// observer's deinit cancels every subscription without an `assumeIsolated` hop.
@MainActor
final class FlowBridge {
    // Lock-guarded so cancellation is isolation-free and callable from a nonisolated deinit.
    // `Task.cancel()` is thread-safe; the lock only protects the array mutation.
    private let tasks = OSAllocatedUnfairLock<[Task<Void, Never>]>(initialState: [])

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
        tasks.withLock { $0.append(task) }
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

    /// Cancel every subscription. Safe to call from any thread (e.g. a nonisolated deinit) —
    /// cancelling a `Task` needs no actor isolation; the lock guards the array mutation.
    nonisolated func cancelAll() {
        tasks.withLock { list in
            for task in list { task.cancel() }
            list.removeAll()
        }
    }
}
