import Foundation
import Shared

// MARK: - Native projections

/// Which field one bulk-edit instruction changes.
///
/// A native enum rather than the bridged `BulkEdit` because this is what reaches the preview's
/// `ForEach`: a Swift-Export-bridged Kotlin object re-bridges every property read on every SwiftUI
/// diff (rule 8). The Kotlin instruction never leaves the observer.
enum BulkEditField: String, Sendable, CaseIterable {
    case publisher
    case year
    case language
    case series
    case contributors
    case genres
    case tags
    case moods

    /// The field's name, as the form labels it. Shared with the Compose editor's preview rows so
    /// the two platforms describe the same edit with the same words.
    var label: String {
        switch self {
        case .publisher: String(localized: "bulk_edit.publisher")
        case .year: String(localized: "bulk_edit.year")
        case .language: String(localized: "bulk_edit.language")
        case .series: String(localized: "bulk_edit.series")
        case .contributors: String(localized: "bulk_edit.contributors")
        case .genres: String(localized: "bulk_edit.genres")
        case .tags: String(localized: "bulk_edit.tags")
        case .moods: String(localized: "bulk_edit.moods")
        }
    }
}

/// One line of the "what will this do" summary — a named field and how much of the selection it
/// actually touches.
///
/// The count deliberately excludes books the instruction would not change. A bulk edit has no undo,
/// so a number that quietly included untouched books would overstate what Apply does.
struct BulkEditPreviewLine: Identifiable, Equatable, Sendable {
    let id: String
    let label: String
    let detail: String
    /// True when this instruction changes nothing — rendered dimmed rather than hidden, because a
    /// row that vanished would read as a lost edit.
    let changesNothing: Bool
}

// MARK: - Mapping

/// Pure state→native mapping for the bulk editor. Statics only, so every projection the screen
/// renders is reachable from a test without a live `BulkEditViewModel`.
enum BulkEditMapping {
    /// The field a bridged instruction changes, or nil when Swift cannot name it.
    ///
    /// Swift cannot switch a Kotlin sealed interface exhaustively, so `onEnum(of:)` always carries
    /// an `.unknown` branch. A `BulkEdit` variant this build does not know about is logged and
    /// dropped rather than rendered as a blank row — an unnamed row in a destructive preview is
    /// worse than one fewer row, and the log is what makes the omission findable.
    static func field(of edit: BulkEdit) -> BulkEditField? {
        switch onEnum(of: edit) {
        case .setPublisher: return .publisher
        case .setPublishYear: return .year
        case .setLanguage: return .language
        case .addToSeries: return .series
        case .addContributors: return .contributors
        case .addGenres: return .genres
        case .addTags: return .tags
        case .addMoods: return .moods
        case .unknown:
            Log.error("Unexpected BulkEdit case in preview")
            return nil
        }
    }

    /// One preview line for a named field.
    static func previewLine(
        field: BulkEditField,
        affectedCount: Int,
        bookCount: Int
    ) -> BulkEditPreviewLine {
        BulkEditPreviewLine(
            id: field.rawValue,
            label: field.label,
            detail: BulkEditFormatting.affects(affectedCount: affectedCount, bookCount: bookCount),
            changesNothing: affectedCount == 0
        )
    }

    /// Narrow a locally-held catalogue to what a typed query offers.
    ///
    /// Genres, tags and moods are filtered **here**, not by a query to the server, for the same
    /// reason search is: the lists are already in Room, so the picker works with the network off.
    /// An empty query offers the whole catalogue — the *caller* gates on whether anything has been
    /// typed, because the Compose editor learned on a device that returning everything for a blank
    /// query leaves the dropdown permanently open over the rest of the form.
    static func narrow(
        _ catalogue: [RelationSearchResult],
        query: String,
        excluding chosenIds: Set<String>
    ) -> [RelationSearchResult] {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        return catalogue
            .filter { !chosenIds.contains($0.id) }
            .filter { trimmed.isEmpty || $0.name.localizedCaseInsensitiveContains(trimmed) }
    }

    /// The chip for a credited contributor.
    ///
    /// Keyed on name **and** role, so the same person credited twice — author and narrator of their
    /// own memoir — is two chips rather than one that cannot be told apart.
    static func contributorChip(name: String, roleApiValue: String) -> EditableRelation {
        EditableRelation(
            id: "\(name)/\(roleApiValue)",
            label: "\(name) · \(BookEditObserver.roleTitle(roleApiValue: roleApiValue))"
        )
    }

    /// The chip for a chosen genre. Falls back to the id when the catalogue cannot name it, which
    /// happens only if the genre was deleted from another device mid-edit — an id is ugly and
    /// findable; a blank chip is neither.
    static func genreChip(id: String, catalogue: [RelationSearchResult]) -> EditableRelation {
        EditableRelation(id: id, label: catalogue.first { $0.id == id }?.name ?? id)
    }

    /// The chip for a tag or mood. These travel by **display name**, not slug — the server's
    /// find-or-create keys on the normalised name, so passing a slug mints a tag literally called
    /// `found-family`.
    static func nameChip(_ name: String) -> EditableRelation {
        EditableRelation(id: name, label: name)
    }

    /// The whole preview panel, in the order the shared ViewModel produced it.
    static func previewLines(_ rows: [BulkEditPreviewRow], bookCount: Int) -> [BulkEditPreviewLine] {
        rows.compactMap { row in
            guard let field = field(of: row.edit) else { return nil }
            return previewLine(field: field, affectedCount: Int(row.affectedCount), bookCount: bookCount)
        }
    }
}

// MARK: - Formatting

/// Every sentence the bulk editor speaks, as pure functions over counts.
///
/// Separated from the views so the choice of wording — which is where a bulk edit lies about itself
/// if it lies at all — is unit-testable rather than only visible on a simulator.
enum BulkEditFormatting {
    /// The sheet title. One book gets its own sentence rather than a counted one.
    static func title(bookCount: Int) -> String {
        bookCount == 1
            ? String(localized: "bulk_edit.title_one")
            : String(format: String(localized: "bulk_edit.title_plural"), bookCount)
    }

    /// What Apply promises, counted in books that will **change** — never in books that were
    /// selected.
    ///
    /// Zero is named rather than counted. "Change 0 books" is true, but it is the resting state of
    /// an untouched form and a count of nothing reads as a bug rather than an invitation.
    static func applyLabel(changedBookCount: Int) -> String {
        switch changedBookCount {
        case 0: String(localized: "bulk_edit.apply_none")
        case 1: String(localized: "bulk_edit.apply_one")
        default: String(format: String(localized: "bulk_edit.apply_plural"), changedBookCount)
        }
    }

    /// How much of the selection one instruction touches, in words. A single selected book gets its
    /// own sentence — "1 of 1 books change" is a sentence nobody needs to parse.
    static func affects(affectedCount: Int, bookCount: Int) -> String {
        if affectedCount == 0 {
            return String(localized: "bulk_edit.preview_affects_none")
        }
        if bookCount == 1 {
            return String(localized: "bulk_edit.preview_affects_single_book")
        }
        return String(format: String(localized: "bulk_edit.preview_affects_plural"), affectedCount, bookCount)
    }

    /// The books that were chosen but could not be read, or nil when none are missing.
    ///
    /// Silent in the normal case. When the selection has shrunk — a book deleted from another
    /// device between the grid and this sheet is the realistic way — the shortfall is stated, because
    /// an operation with no undo does not get to quietly do less than it was asked to.
    static func notLoadedNotice(bookCount: Int, requestedCount: Int) -> String? {
        let missing = requestedCount - bookCount
        guard missing > 0 else { return nil }
        return missing == 1
            ? String(format: String(localized: "bulk_edit.some_not_loaded_one"), requestedCount)
            : String(format: String(localized: "bulk_edit.some_not_loaded_plural"), missing, requestedCount)
    }

    /// The hint shown in an untouched field: the value the whole selection already agrees on, or
    /// "Multiple values" when they differ. Placeholder, never value — a value would make an
    /// untouched field indistinguishable from an edited one, and Apply would rewrite it.
    static func placeholder(shared: String?) -> String {
        shared ?? String(localized: "bulk_edit.multiple_values")
    }

    /// What the app says once a bulk edit has landed.
    ///
    /// The sheet dismisses the moment it succeeds and the grid it returns to shows covers and
    /// titles — not publishers — so without this a write to forty books looks exactly like a write
    /// to none. Counts books that **changed**, matching the Apply button that promised it.
    static func applied(changedCount: Int) -> String {
        changedCount == 1
            ? String(localized: "bulk_edit.applied_one")
            : String(format: String(localized: "bulk_edit.applied_plural"), changedCount)
    }

    /// What the failure alert says: why it stopped, plus how much stands.
    ///
    /// The committed books are not rolled back, so naming them is the difference between "nothing
    /// happened" and "some of it happened" — which is the only thing the user can act on.
    static func failureMessage(reason: String, appliedCount: Int) -> String {
        guard appliedCount > 0 else { return reason }
        let tail = appliedCount == 1
            ? String(localized: "bulk_edit.failed_after_one")
            : String(format: String(localized: "bulk_edit.failed_after_plural"), appliedCount)
        return "\(reason)\n\n\(tail)"
    }
}
