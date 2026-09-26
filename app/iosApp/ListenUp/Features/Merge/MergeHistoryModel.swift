import Foundation
import Shared

/// One merge folded into a series or genre, as the "Merged into this" list draws it (#1061).
/// A native value: the list maps the Kotlin state once per emission and never re-reads the bridge.
struct MergeRow: Identifiable, Equatable {
    let id: String
    let sourceName: String
    let mergedAt: Date
    let mergedByName: String?
    let bookCount: Int
}

/// What an undo put back, in the numbers the sentence needs.
struct MergeOutcomeModel: Equatable {
    let sourceName: String
    let booksRestored: Int
    let booksSkipped: Int
    let restoredAtTopLevel: Bool
}

/// The "Merged into this" section, flattened from the shared `MergeHistoryState`.
enum MergeHistoryModel: Equatable {
    case loading
    /// The list could not be read — usually no connection; the message says which.
    case unavailable(String)
    case ready(rows: [MergeRow], undoingId: String?, outcome: MergeOutcomeModel?)

    /// `nonisolated`-safe mapping from the Kotlin state, read once per emission.
    static func from(_ state: MergeHistoryState) -> MergeHistoryModel {
        switch state.sealedType() {
        case .loading:
            return .loading
        case .unavailable(let type):
            return .unavailable(type.value.error.message)
        case .ready(let type):
            let ready = type.value
            return .ready(
                rows: Array(ready.receipts).map { receipt in
                    MergeRow(
                        id: receipt.id.value,
                        sourceName: receipt.sourceName,
                        mergedAt: Date(timeIntervalSince1970: TimeInterval(receipt.mergedAt) / 1_000),
                        mergedByName: receipt.mergedByName,
                        bookCount: Int(receipt.bookCount)
                    )
                },
                undoingId: ready.undoingId?.value,
                outcome: ready.outcome.map { outcome in
                    MergeOutcomeModel(
                        sourceName: outcome.sourceName,
                        booksRestored: Int(outcome.booksRestored),
                        booksSkipped: Int(outcome.booksSkipped),
                        restoredAtTopLevel: outcome.restoredAtTopLevel
                    )
                }
            )
        }
    }
}

/// The sentences the list says, kept pure so they are testable without a view.
enum MergeHistoryText {
    /// "Up to 4 books · September 25, 2026 · by Simon" — the name only while the account exists.
    static func detail(_ row: MergeRow) -> String {
        let books = String(
            format: String(localized: row.bookCount == 1 ? "merge_history.row_books" : "merge_history.row_books_plural"),
            Int32(row.bookCount)
        )
        let date = row.mergedAt.formatted(date: .long, time: .omitted)
        let by = row.mergedByName.map { String(format: String(localized: "merge_history.row_by"), $0) }
        return [books, date, by].compactMap { $0 }.joined(separator: " · ")
    }

    /// Every line the outcome says: what came back, what stayed, and where a genre landed.
    static func outcome(_ outcome: MergeOutcomeModel) -> [String] {
        var lines = [
            String(
                format: String(
                    localized: outcome.booksRestored == 1 ? "merge_history.outcome" : "merge_history.outcome_plural"
                ),
                outcome.sourceName,
                Int32(outcome.booksRestored)
            )
        ]
        if outcome.booksSkipped > 0 {
            lines.append(
                String(
                    format: String(
                        localized: outcome.booksSkipped == 1 ? "merge_history.skipped" : "merge_history.skipped_plural"
                    ),
                    Int32(outcome.booksSkipped)
                )
            )
        }
        if outcome.restoredAtTopLevel { lines.append(String(localized: "merge_history.top_level")) }
        return lines
    }
}
