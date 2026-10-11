import Observation
import UIKit
import Shared

/// Keeps the app icon's long-press list showing the four most recent books.
///
/// The loader and the sink are injected (production reads the shared `HomeRepository` and writes
/// `UIApplication.shared.shortcutItems`), so the observer is tested without Koin or UIKit.
/// Callers refresh on the moments Android does — signed in, and whenever the current book changes —
/// and `clear()` on sign-out.
@Observable
@MainActor
final class RecentBookShortcutsObserver {
    private let bundleIdentifier: String
    private let loadRecentBooks: @MainActor () async -> [RecentBook]
    private let publish: @MainActor ([UIApplicationShortcutItem]) -> Void
    private var currentBookTask: Task<Void, Never>?

    init(
        bundleIdentifier: String = Bundle.main.bundleIdentifier ?? "com.calypsan.listenup.client.ios",
        loadRecentBooks: @escaping @MainActor () async -> [RecentBook] = RecentBookShortcutsObserver.loadFromHome,
        publish: @escaping @MainActor ([UIApplicationShortcutItem]) -> Void = { items in
            UIApplication.shared.shortcutItems = items
        }
    ) {
        self.bundleIdentifier = bundleIdentifier
        self.loadRecentBooks = loadRecentBooks
        self.publish = publish
    }

    /// Re-reads the recent books and publishes them as quick actions.
    func refresh() async {
        let books = await loadRecentBooks()
        publish(RecentBookQuickActions.items(for: books, bundleIdentifier: bundleIdentifier))
    }

    /// Removes the quick actions (sign-out): a signed-out device must not list someone's books.
    func clear() {
        stopObservingCurrentBook()
        publish([])
    }

    /// Refreshes now and again each time the player's current book changes. Idempotent.
    func startObservingCurrentBook(of coordinator: PlayerCoordinator) {
        guard currentBookTask == nil else { return }
        currentBookTask = Task { [weak self] in
            for await _ in Observations({ coordinator.currentBookId }) {
                if Task.isCancelled { break }
                await self?.refresh()
            }
        }
    }

    func stopObservingCurrentBook() {
        currentBookTask?.cancel()
        currentBookTask = nil
    }

    /// The production loader. `getRecentBooksOrEmpty` folds the `AppResult` in Kotlin; Swift never
    /// awaits one. Mapped to native values here, off the UIKit path.
    nonisolated static func loadFromHome() async -> [RecentBook] {
        let limit = Int32(RecentBookQuickActions.maxItems)
        let books = (try? await Dependencies.shared.homeRepository.getRecentBooksOrEmpty(limit: limit)) ?? []
        return books.map { RecentBook(id: $0.bookId, title: $0.title, author: $0.authorNames) }
    }
}
