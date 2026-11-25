import SwiftUI

/// Sentinel identity for `WithObserver` sites whose observer lives for the view's entire SwiftUI identity.
struct ObserverDefaultID: Equatable {}

/// Holds a lazily created screen-scoped observer (or tuple of observers) across SwiftUI re-renders.
///
/// Stored in `@State` as a lightweight empty box (`nonisolated init()`), then populated once on the first
/// `@MainActor` `body` pass without mutating `@State` or allocating wasted instances on parent redraws.
/// Changing `id` replaces the stored value immediately, releasing the previous observer (and its
/// `FlowBridge` / owned Kotlin ViewModel). Releasing the slot runs `isolated deinit` on `@MainActor`.
final class ObserverSlot<ID: Equatable, Value> {
    private var entry: (id: ID, value: Value)?

    nonisolated init() {}

    func resolve(id: ID, make: () -> Value) -> Value {
        if let entry, entry.id == id {
            return entry.value
        }
        let created = make()
        entry = (id, created)
        return created
    }

    isolated deinit {}
}

/// Hosts a screen-scoped observer created once from `@Environment(\.dependencies)` and hands it
/// non-optionally to `content`, eliminating `@State private var observer: T?`, the pre-`onAppear`
/// `nil` frame, and repeated `if observer == nil` boilerplate.
struct WithObserver<ID: Equatable, Observer, Content: View>: View {
    @Environment(\.dependencies) private var deps
    @State private var slot = ObserverSlot<ID, Observer>()

    private let id: ID
    private let make: @MainActor (Dependencies) -> Observer
    private let content: @MainActor (Observer) -> Content

    init(
        id: ID,
        make: @escaping @MainActor (Dependencies) -> Observer,
        @ViewBuilder content: @escaping @MainActor (Observer) -> Content
    ) {
        self.id = id
        self.make = make
        self.content = content
    }

    var body: some View {
        content(slot.resolve(id: id) { make(deps) })
    }
}

extension WithObserver where ID == ObserverDefaultID {
    init(
        make: @escaping @MainActor (Dependencies) -> Observer,
        @ViewBuilder content: @escaping @MainActor (Observer) -> Content
    ) {
        self.init(id: ObserverDefaultID(), make: make, content: content)
    }
}
