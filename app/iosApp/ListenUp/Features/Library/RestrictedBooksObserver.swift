import SwiftUI
import Shared

/// Observes `RestrictedBooksViewModel`: the books an admin should see locked on every cover card.
/// Empty for anyone who is not an admin, so no card needs a role check. Published once, through the
/// environment, from `MainTabView`.
@Observable
@MainActor
final class RestrictedBooksObserver {
    private(set) var restrictedBookIds: Set<String> = []

    private let viewModel: RestrictedBooksViewModel
    private let bridge = FlowBridge()

    init(viewModel: RestrictedBooksViewModel) {
        self.viewModel = viewModel
        bridge.bind(viewModel.restrictedBookIds) { [weak self] ids in self?.restrictedBookIds = Set(ids) }
    }

    /// Whether `bookId`'s cover wears the lock.
    func isRestricted(_ bookId: String) -> Bool {
        restrictedBookIds.contains(bookId)
    }

    // Isolated deinit (SE-0371): runs on the main actor so the non-Sendable Kotlin viewModel can be
    // closed — a fresh factory instance per shell, whose stream jobs would otherwise outlive it (#1192).
    isolated deinit {
        bridge.cancelAll()   // cancelAll() is nonisolated-safe; see FlowBridge.
        viewModel.close()
    }
}

extension EnvironmentValues {
    /// The shell's restricted-book set; nil until `MainTabView` appears (and so, no locks).
    @Entry var restrictedBooks: RestrictedBooksObserver?
}
