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

    /// The glyph that stands for the field — the same one the form uses, where the two overlap.
    var symbol: String {
        switch self {
        case .publisher: "building.2"
        case .year: "calendar"
        case .language: "globe"
        case .series: "books.vertical"
        case .contributors: "person.2"
        case .genres: "tag"
        case .tags: "number"
        case .moods: "face.smiling"
        }
    }
}

/// One field's consequence line, resolved: the whole sentence, its marker, and whether it earns
/// the accent colour. Three states, and they are the whole point of the screen:
///  - **typed** — the field has produced an instruction, so it counts the books that would actually
///    change. The count is the field's own preview row, never the screen's total.
///  - **agreed** — untouched, and every loaded book already says the same thing. The value is named
///    so the reader can see what they would be replacing without risking it.
///  - **differs** — untouched, and there is no single value to name. Nothing is written either way.
struct FieldConsequence: Equatable, Sendable {
    let text: String
    /// An arrow when something will be written, a dash when a typed value changes nothing, an open
    /// padlock when the field is untouched and safe.
    let symbol: String
    /// True only when at least one book would actually be written to.
    let writes: Bool
}

/// One line of the "what will this do" summary — a named field and how much of the selection it
/// actually touches.
///
/// The count deliberately excludes books the instruction would not change. A bulk edit has no undo,
/// so a number that quietly included untouched books would overstate what Apply does.
struct BulkEditPreviewLine: Identifiable, Equatable, Sendable {
    let id: String
    let label: String
    let symbol: String
    let detail: String
    /// The count again in a form nobody has to count: affected over selected, 0...1.
    let fraction: Double
    /// Why the rest of the selection is left alone, or nil when there is no rest to account for or
    /// no single value to name it with.
    let note: String?
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
    /// Swift cannot switch a Kotlin sealed interface exhaustively, so `.sealedType()` always carries
    /// an `.unknown` branch. A `BulkEdit` variant this build does not know about is logged and
    /// dropped rather than rendered as a blank row — an unnamed row in a destructive preview is
    /// worse than one fewer row, and the log is what makes the omission findable.
    static func field(of edit: BulkEdit) -> BulkEditField? {
        switch edit.sealedType() {
        case .setPublisher: return .publisher
        case .setPublishYear: return .year
        case .setLanguage: return .language
        case .addToSeries: return .series
        case .addContributors: return .contributors
        case .addGenres: return .genres
        case .addTags: return .tags
        case .addMoods: return .moods
        }
    }

    /// One preview line for a named field.
    static func previewLine(
        field: BulkEditField,
        affectedCount: Int,
        bookCount: Int,
        value: String? = nil
    ) -> BulkEditPreviewLine {
        BulkEditPreviewLine(
            id: field.rawValue,
            label: field.label,
            symbol: field.symbol,
            detail: BulkEditFormatting.affects(affectedCount: affectedCount, bookCount: bookCount),
            fraction: bookCount <= 0 ? 0 : Double(affectedCount) / Double(bookCount),
            note: BulkEditFormatting.leftAloneNote(value: value, affectedCount: affectedCount, bookCount: bookCount),
            changesNothing: affectedCount == 0
        )
    }

    /// The value an instruction writes, when it is a single value a sentence can name. The
    /// collection instructions are nil rather than a joined list: "Fantasy, Grimdark, Space Opera
    /// already say…" is not a sentence, and half a sentence in a destructive preview is worse than
    /// none.
    static func value(of edit: BulkEdit) -> String? {
        switch edit.sealedType() {
        case .setPublisher(let type): type.value.publisher
        case .setPublishYear(let type): String(type.value.year)
        case .setLanguage(let type): type.value.language
        case .addToSeries, .addContributors, .addGenres, .addTags, .addMoods: nil
        }
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

    /// The consequence line under every field. The three publishing fields always have one — armed
    /// or untouched — because they can name what the books already say; a relation field has one
    /// only while armed, because "add a genre" has no agreed value to fall back to.
    static func consequences(
        affectedByField: [BulkEditField: Int],
        bookCount: Int,
        sharedPublisher: String?,
        sharedYear: String?,
        sharedLanguage: String?
    ) -> [BulkEditField: FieldConsequence] {
        var lines: [BulkEditField: FieldConsequence] = [:]
        let shared: [BulkEditField: String?] = [
            .publisher: sharedPublisher,
            .year: sharedYear,
            .language: sharedLanguage
        ]
        for (field, value) in shared {
            lines[field] = BulkEditFormatting.consequence(
                armedAffectedCount: affectedByField[field],
                bookCount: bookCount,
                sharedValue: value
            )
        }
        for field in [BulkEditField.series, .contributors, .genres, .tags, .moods] {
            if let affected = affectedByField[field] {
                lines[field] = BulkEditFormatting.armedConsequence(affected: affected, bookCount: bookCount)
            }
        }
        return lines
    }

    /// The whole preview panel, in the order the shared ViewModel produced it.
    static func previewLines(_ rows: [BulkEditPreviewRow], bookCount: Int) -> [BulkEditPreviewLine] {
        rows.compactMap { row in
            guard let field = field(of: row.edit) else { return nil }
            return previewLine(
                field: field,
                affectedCount: Int(row.affectedCount),
                bookCount: bookCount,
                value: value(of: row.edit)
            )
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

    /// What one field promises, given whether it is armed and what the selection agrees on.
    ///
    /// `armedAffectedCount` is the field's own preview row when it has produced an instruction, and
    /// nil when it is untouched. `sharedValue` is the value every loaded book already holds, or nil
    /// when they differ. Mirrors the Compose editor's `consequenceOf` decision for decision, so the
    /// two platforms make the same promise in the same words.
    static func consequence(
        armedAffectedCount: Int?,
        bookCount: Int,
        sharedValue: String?
    ) -> FieldConsequence {
        if let affected = armedAffectedCount {
            return armedConsequence(affected: affected, bookCount: bookCount)
        }
        let text: String =
            switch (sharedValue, bookCount) {
            case (let value?, 1):
                String(format: String(localized: "bulk_edit.consequence_agreed_one"), value)
            case (let value?, _):
                String(format: String(localized: "bulk_edit.consequence_agreed_plural"), bookCount, value)
            case (nil, 1):
                String(localized: "bulk_edit.consequence_differs_one")
            case (nil, _):
                String(format: String(localized: "bulk_edit.consequence_differs_plural"), bookCount)
            }
        return FieldConsequence(text: text, symbol: "lock.open", writes: false)
    }

    /// What an **armed** field promises. Shared with the relation fields, which arm and disarm as
    /// the text fields do but have no "the books already agree" state to fall back to — a genre is
    /// not a value a selection can share, it is a thing each book either carries or does not.
    static func armedConsequence(affected: Int, bookCount: Int) -> FieldConsequence {
        switch (affected, bookCount) {
        case (0, _):
            FieldConsequence(
                text: String(localized: "bulk_edit.consequence_written_none"),
                symbol: "minus",
                writes: false
            )
        case (_, 1):
            FieldConsequence(
                text: String(localized: "bulk_edit.consequence_written_single_book"),
                symbol: "arrow.right",
                writes: true
            )
        case (1, _):
            FieldConsequence(
                text: String(format: String(localized: "bulk_edit.consequence_written_one"), bookCount),
                symbol: "arrow.right",
                writes: true
            )
        default:
            FieldConsequence(
                text: String(format: String(localized: "bulk_edit.consequence_written_plural"), affected, bookCount),
                symbol: "arrow.right",
                writes: true
            )
        }
    }

    /// Why the rest of the selection is left alone, or nil when there is no rest to account for.
    /// Silent when the instruction changes every book — there is nothing left to explain, and
    /// filler is how readers learn to skip the lines that matter.
    static func leftAloneNote(value: String?, affectedCount: Int, bookCount: Int) -> String? {
        guard let value else { return nil }
        let leftAlone = bookCount - affectedCount
        switch leftAlone {
        case ...0: return nil
        case 1: return String(format: String(localized: "bulk_edit.preview_note_one"), value)
        default: return String(format: String(localized: "bulk_edit.preview_note_plural"), leftAlone, value)
        }
    }

    /// The hero's eyebrow: where the selection came from and how big it is.
    static func heroEyebrow(selectedCount: Int) -> String {
        String(format: String(localized: "bulk_edit.hero_eyebrow"), selectedCount)
    }

    /// The chip after the cover cluster naming the books it had no room for, or nil when it showed
    /// them all.
    static func heroMore(bookCount: Int, shown: Int) -> String? {
        let remaining = bookCount - shown
        return remaining > 0 ? String(format: String(localized: "bulk_edit.hero_more"), remaining) : nil
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
