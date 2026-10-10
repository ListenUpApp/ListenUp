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
/// An observer does not write a `deinit` for its bridge. When the bridge is released it cancels
/// its subscriptions and clears whatever it owns (`own(_:)` adopts a fresh Kotlin ViewModel),
/// so the screen's ViewModel ends with the screen. `cancelAll()` stays callable from any
/// thread for the cases that re-bind: the task list is guarded by an `OSAllocatedUnfairLock`,
/// and `Task.cancel()` is itself thread-safe.
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

    /// Cancel every subscription. Safe to call from any thread — cancelling a `Task` needs no
    /// actor isolation; the lock guards the array mutation. For the observers that re-bind;
    /// ending the bridge's life is `release()`, which a deinit reaches by itself.
    nonisolated func cancelAll() {
        tasks.withLock { list in
            for task in list { task.cancel() }
            list.removeAll()
        }
    }

    /// What a bridge calls a Kotlin ViewModel: the base type every shared ViewModel extends.
    typealias KotlinViewModel = ExportedKotlinPackages.androidx.lifecycle.ViewModel

    // Run once, on the main actor, when the bridge is released or `release()` is called.
    private var releaseActions: [@MainActor () -> Void] = []

    /// Run `action` when this bridge is released, after its subscriptions are cancelled.
    func onRelease(_ action: @escaping @MainActor () -> Void) {
        releaseActions.append(action)
    }

    /// Adopt a screen-scoped Kotlin ViewModel: it is cleared when this bridge is released.
    ///
    /// iOS has no `ViewModelStore`, so without an owner a ViewModel's scope — and every flow it
    /// collects — outlives its screen. Only for a ViewModel this observer was given fresh (a
    /// `make…`/`create…` call); never for one of `Dependencies`' shared instances.
    func own(_ viewModel: KotlinViewModel) {
        onRelease { KoinHelper.shared.closeViewModel(viewModel: viewModel) }
    }

    /// Cancel every subscription and run the release actions now. Idempotent.
    func release() {
        cancelAll()
        let actions = releaseActions
        releaseActions.removeAll()
        for action in actions { action() }
    }

    // Isolated (SE-0371) so the release actions, which touch non-Sendable Kotlin objects, run on
    // the main actor whichever thread drops the last reference.
    isolated deinit {
        release()
    }
}
