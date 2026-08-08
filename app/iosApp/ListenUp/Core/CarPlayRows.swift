import Foundation

/// One row in a CarPlay list.
///
/// A native value type on purpose: `CPListItem`s are rebuilt whenever a template is refreshed,
/// and reading a Swift-Export-bridged Kotlin object per rebuild re-bridges every string across
/// the boundary. Same rule as the phone's `ForEach` rows (`app/iosApp/CLAUDE.md` rule 8) — map
/// once, at the boundary, into plain Swift.
struct CarPlayRow: Equatable, Identifiable {
    /// Book id — what selecting the row hands to `PlayerCoordinator.play(bookId:)`.
    let id: String
    let title: String
    /// The secondary line: author and remaining time, whichever of them exist.
    let detailText: String
    let isPlayable: Bool
    /// Local cover-image file path, `nil` when no cover is cached — the row renders text-only.
    /// A path, not a `UIImage`: image resolution is the delegate's concern, keeping this type a
    /// pure value the mapping tests can assert on.
    let coverPath: String?
}

/// Builds [CarPlayRow]s from the app's existing native view data.
enum CarPlayRows {
    /// Mirrors Android Auto's `CONTINUE_LISTENING_LIMIT`. A curated shelf, not a truncated
    /// library — the two car surfaces are deliberately kept at the same number.
    static let continueListeningLimit = 8

    /// Maps the continue-listening shelf to car rows.
    ///
    /// Placeholders are dropped before the cap, not after: a shelf padded with in-flight items
    /// would otherwise offer a driver fewer than the eight real books the limit promises.
    static func continueListening(from items: [ContinueItem]) -> [CarPlayRow] {
        items
            .lazy
            .filter { !$0.isLoading }
            .prefix(continueListeningLimit)
            .map { item in
                CarPlayRow(
                    id: item.id,
                    title: item.title,
                    detailText: detailText(author: item.author, timeLeft: item.timeLeft),
                    isPlayable: true,
                    coverPath: item.coverPath
                )
            }
    }

    /// Joins the parts of a row's secondary line, skipping whichever are absent so a missing
    /// author or an unknown remaining time never leaves a dangling separator.
    static func detailText(author: String, timeLeft: String) -> String {
        [author, timeLeft]
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .joined(separator: " • ")
    }
}
