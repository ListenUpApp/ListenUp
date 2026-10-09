import Foundation
@preconcurrency import Shared

/// Book Detail's last-match row as native values: "Details matched 2 days ago · See What Changed · Undo Last Match".
/// Mapped once at the observer boundary from the shared `LastMatchUi`, so the row never re-bridges Kotlin.
struct LastMatchRowModel: Equatable {
    let receiptId: String
    let appliedAt: Date
    /// Whoever matched the book when it wasn't you; nil when you did.
    let matchedBy: String?
    /// The match as the receipt shows it — See What Changed opens the receipt's own sheet over it.
    let receipt: MatchReceiptModel
    let showingChanges: Bool
    let undoing: Bool
    /// Why the last Undo failed; the row stays so it can be tried again.
    let undoError: String?
}

enum LastMatchMapping {
    /// The shared row, as native values.
    static func row(from ui: LastMatchUi) -> LastMatchRowModel {
        LastMatchRowModel(
            receiptId: ui.receipt.receiptId,
            appliedAt: Date(timeIntervalSince1970: TimeInterval(ui.appliedAtMs) / 1000),
            matchedBy: ui.matchedBy,
            receipt: MatchReceiptModel(
                id: ui.receipt.receiptId,
                sentence: MatchCopy.receipt(ui.receipt),
                changes: ui.receipt.changes.flatMap(MatchCopy.changeLines),
                canUndo: true,
                undoing: ui.undoing,
                undoError: ui.undoError?.message
            ),
            showingChanges: ui.showingChanges,
            undoing: ui.undoing,
            undoError: ui.undoError?.message
        )
    }

    /// How an Undo from the row ended, as the receipt capsule says it.
    static func outcome(from event: any LastMatchEvent) -> MatchReceiptPhase {
        switch event.sealedType() {
        case .undone: .undone
        case .expired: .expired(message: MatchCopy.undoExpired(.book))
        }
    }
}

extension MatchCopy {
    /// "Details matched just now", "Details matched 2 days ago", or "… by Sam" when someone else matched the book.
    static func lastMatch(appliedAt: Date, matchedBy: String?, now: Date) -> String {
        let justNow = now.timeIntervalSince(appliedAt) < 60
        let relative = justNow ? "" : relativeFormatter.localizedString(for: appliedAt, relativeTo: now)
        switch (matchedBy, justNow) {
        case (nil, true):
            return String(localized: "match.details_matched_just_now")
        case (nil, false):
            return String(format: String(localized: "match.details_matched"), relative)
        case (let name?, true):
            return String(format: String(localized: "match.details_matched_by_just_now"), name)
        case (let name?, false):
            return String(format: String(localized: "match.details_matched_by"), relative, name)
        }
    }

    private static var relativeFormatter: RelativeDateTimeFormatter {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        return formatter
    }
}
