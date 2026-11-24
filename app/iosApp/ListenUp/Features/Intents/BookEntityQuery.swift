import AppIntents
@preconcurrency import Shared

/// Resolves `BookEntity` values for App Intents / Siri — the "Play <book>" path.
///
/// Backed by the offline-first `BookRepository`: title matching runs through the
/// same never-stranded `search` used by the in-app search surface (server FTS5
/// online, local Room FTS5 offline), so spoken lookups work without a connection.
struct BookEntityQuery: EntityStringQuery {

    /// Title-or-author match for a spoken/typed phrase. A blank string yields the
    /// repository's empty result. Capped at 10 so Siri's disambiguation list stays
    /// usable.
    func entities(matching string: String) async throws -> [BookEntity] {
        let items = await firstEmission(of: Dependencies.shared.bookRepository.search(query: string).asAsyncSequence())
        return items.prefix(10).map(BookEntity.from)
    }

    /// Re-hydrate known entity ids (Shortcuts persists the selected book by id).
    /// Missing ids — a book deleted since the shortcut was built — drop out.
    func entities(for identifiers: [String]) async throws -> [BookEntity] {
        var results: [BookEntity] = []
        for id in identifiers {
            if let item = try await Dependencies.shared.bookRepository.getBookListItem(id: id) {
                results.append(BookEntity.from(item))
            }
        }
        return results
    }

    /// Suggestions shown in the Shortcuts editor. The repository search treats a
    /// blank query as "no match", so this is intentionally empty rather than a
    /// catalog dump — title resolution happens on the spoken phrase.
    func suggestedEntities() async throws -> [BookEntity] {
        []
    }

    /// Drains the first emission of a Kotlin `Flow` (bridged via `asAsyncSequence()`).
    /// `search` emits exactly once (it's a query, not a subscription), so the first
    /// element is the complete ranked result; a failing/empty flow yields `[]` (never stranded).
    ///
    /// Generic over `AsyncSequence` to avoid naming the generated flow-sequence type —
    /// the same approach `FlowBridge.bind` takes. `@MainActor`-confined so the
    /// non-`Sendable` flow iterator never crosses an isolation boundary.
    private func firstEmission<S: AsyncSequence>(of sequence: S) async -> [BookListItem]
    where S.Element == [BookListItem] {
        do {
            for try await value in sequence {
                return value
            }
        } catch {
            // Kotlin flow failed — fall through to the empty, never-stranded result.
        }
        return []
    }
}
