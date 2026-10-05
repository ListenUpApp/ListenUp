import SwiftUI
import Shared

// Native projections for the editor's "Place in library" section and its "Move into…" picker.
// Mapped once in `SeriesEditObserver.apply`, never fed to a `ForEach` as bridged Kotlin objects.

/// One of the series' own sub-series in the editor's reorderable list.
struct EditableSubSeries: Identifiable, Hashable {
    let id: String
    let name: String
    let bookCount: Int

    var meta: String { SeriesHierarchyText.books(bookCount) }
}

extension EditableSubSeries {
    init(_ candidate: SeriesCandidate) {
        self.init(id: candidate.id.value, name: candidate.displayName, bookCount: Int(candidate.bookCount))
    }
}

/// Why a "Move into…" row can't be chosen.
enum ParentPickerReason: Hashable {
    /// Already the parent — "Current".
    case currentParent
    /// The series being moved — "This series".
    case thisSeries
    /// Inside the series being moved; choosing it would loop — "Inside Mistborn".
    case insideThisSeries

    /// The words shown beside the row, and read as its VoiceOver value.
    func text(seriesName: String) -> String {
        switch self {
        case .currentParent: String(localized: "series.picker_current")
        case .thisSeries: String(localized: "series.picker_self")
        case .insideThisSeries: String(format: String(localized: "series.picker_inside"), seriesName)
        }
    }
}

/// One row of the "Move into…" picker. The whole tree stays listed: rows the cycle check rules out
/// are greyed with a reason, so the shape of the library never jumps.
struct ParentPickerItem: Identifiable, Hashable {
    let id: String
    let name: String
    /// 0 at the top level; always 0 while searching.
    let depth: Int
    /// The series above this one, root first — shown while searching.
    let pathNames: [String]
    let bookCount: Int
    let subSeriesCount: Int
    let isExpanded: Bool
    let reason: ParentPickerReason?

    var hasChildren: Bool { subSeriesCount > 0 }
    var isSelectable: Bool { reason == nil }

    /// "5 series · 41 books", "7 books"; while searching, where it sits: "in Cosmere · 8 books".
    var meta: String? {
        if !pathNames.isEmpty { return SeriesHierarchyText.placement(path: pathNames, bookCount: bookCount) }
        if subSeriesCount > 0 {
            return SeriesHierarchyText.seriesAndBooks(seriesCount: subSeriesCount, bookCount: bookCount)
        }
        return bookCount > 0 ? SeriesHierarchyText.books(bookCount) : nil
    }
}

extension ParentPickerItem {
    init(_ row: ParentPickerRow) {
        let reason: ParentPickerReason? =
            switch row.disabledReason {
            case .currentParent: .currentParent
            case .thisSeries: .thisSeries
            case .insideThisSeries: .insideThisSeries
            case nil: nil
            }
        self.init(
            id: row.id,
            name: row.name,
            depth: Int(row.depth),
            pathNames: Array(row.pathNames),
            bookCount: Int(row.bookCount),
            subSeriesCount: Int(row.subSeriesCount),
            isExpanded: row.isExpanded,
            reason: reason
        )
    }
}

/// The new order of the sub-series after a reorder. Pure, so the one `ChildSeriesReordered` per drop
/// is unit-tested.
enum SubSeriesOrder {
    /// The order after a List drag (`.onMove`'s offsets).
    static func moving(_ ids: [String], fromOffsets source: IndexSet, toOffset destination: Int) -> [String] {
        var reordered = ids
        reordered.move(fromOffsets: source, toOffset: destination)
        return reordered
    }

    /// The order after "Move earlier" (`step` -1) or "Move later" (+1); nil at either end.
    static func stepping(_ ids: [String], id: String, by step: Int) -> [String]? {
        guard let index = ids.firstIndex(of: id) else { return nil }
        let target = index + step
        guard ids.indices.contains(target) else { return nil }
        var reordered = ids
        reordered.swapAt(index, target)
        return reordered
    }
}
