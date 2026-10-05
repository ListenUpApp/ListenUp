import Foundation
import Shared

// Native, value-typed projections of the series hierarchy for SwiftUI. Every Kotlin type here is
// Swift Export-bridged; feeding one into a `ForEach`/`List` re-bridges its properties on every diff
// (iosApp rule 8), so each is snapshotted once, in the observer's `apply`, into one of these.

/// One step of a series' breadcrumb — "Cosmere" above "Mistborn".
struct SeriesCrumbItem: Identifiable, Hashable {
    let id: String
    let name: String
}

extension SeriesCrumbItem {
    init(_ crumb: SeriesCrumb) {
        self.init(id: crumb.id, name: crumb.name)
    }
}

/// A sub-series card on a parent series' page.
struct ChildSeriesCard: Identifiable, Hashable {
    let id: String
    let name: String
    let coverPath: String?
    let bookCount: Int
    let finishedCount: Int
    /// Series directly inside this one; above zero the card draws a stacked edge and "2 series".
    let subSeriesCount: Int

    /// "8 books · 2 finished"
    var meta: String { SeriesHierarchyText.childMeta(bookCount: bookCount, finishedCount: finishedCount) }

    /// "2 series", or nil for a series without sub-series of its own.
    var subSeriesHint: String? { subSeriesCount > 0 ? SeriesHierarchyText.subSeries(subSeriesCount) : nil }

    /// The finished share, for the card's bar.
    var progress: Float { bookCount > 0 ? Float(finishedCount) / Float(bookCount) : 0 }

    /// The card as one VoiceOver element: "Mistborn, 2 series, 8 books, 2 finished".
    var accessibilityLabel: String {
        SeriesHierarchyText.childAccessibilityLabel(
            name: name,
            subSeriesCount: subSeriesCount,
            bookCount: bookCount,
            finishedCount: finishedCount
        )
    }
}

extension ChildSeriesCard {
    init(_ child: ChildSeriesUi) {
        self.init(
            id: child.id,
            name: child.name,
            coverPath: child.coverPath,
            bookCount: Int(child.bookCount),
            finishedCount: Int(child.finishedCount),
            subSeriesCount: Int(child.subSeriesCount)
        )
    }
}

/// What a group of the series page's books is headed by.
enum SeriesGroupKind: Hashable {
    /// A sub-series: the heading links to its page, and the group can fold.
    case subSeries
    /// A series' own books after its sub-series — "Also in Cosmere".
    case ownBooks
}

/// One heading of the series page's book list, with the books under it.
struct SeriesBookGroup: Identifiable, Hashable {
    /// Stable across emissions, for diffing.
    let id: String
    /// The series these books belong to, and the series the heading opens.
    let seriesId: String
    let title: String
    let kind: SeriesGroupKind
    /// From the page's direct sub-series down to this one: "Mistborn", "Mistborn Era 1".
    let path: [String]
    /// 1 directly under the page; deeper for nested sub-series.
    let depth: Int
    /// Each with its number in `seriesId`, not in whatever series the book lists first.
    let books: [BookRow]
    let bookCount: Int
    let finishedCount: Int
    let isCollapsed: Bool

    var isCollapsible: Bool { kind == .subSeries }

    /// The heading: the sub-series' name, its path when nested ("Mistborn › Mistborn Era 1"), or
    /// "Also in Cosmere" for a series' own books.
    var heading: String {
        switch kind {
        case .subSeries: depth >= 2 ? SeriesHierarchyText.path(path) : title
        case .ownBooks: SeriesHierarchyText.alsoIn(title)
        }
    }

    /// "8 books", beside the heading.
    var countLabel: String { SeriesHierarchyText.books(bookCount) }
}

extension SeriesBookGroup {
    init(_ section: SeriesBookSection) {
        let seriesId = section.seriesId
        self.init(
            id: section.key,
            seriesId: seriesId,
            title: section.title,
            kind: section.kind == .subSeries ? .subSeries : .ownBooks,
            path: Array(section.path),
            depth: Int(section.depth),
            books: section.books.map { item in
                BookRow(
                    item,
                    sequence: SeriesSequenceFormatting.label(item.series.first { $0.seriesId == seriesId }?.sequence)
                )
            },
            bookCount: Int(section.bookCount),
            finishedCount: Int(section.finishedCount),
            isCollapsed: section.isCollapsed
        )
    }
}

/// The book a grouped page's Continue resumes, and where it sits.
struct SeriesResumeInfo: Hashable {
    let bookId: String
    let title: String
    let seriesName: String
    let sequence: String?
    /// The ViewModel's word on Start vs Continue, shared with Android and web so the three can't drift.
    let hasStarted: Bool
}

extension SeriesResumeInfo {
    init(_ resume: SeriesResumeUi) {
        self.init(
            bookId: resume.bookId,
            title: resume.title,
            seriesName: resume.seriesName,
            sequence: resume.sequence,
            hasStarted: resume.hasStarted
        )
    }
}

// MARK: - Add sub-series sheet

/// Where a series the "Add sub-series" sheet offers sits today.
enum SubSeriesSpot: Hashable {
    case topLevel
    /// Inside another series; adding it moves it out of there, so the sheet asks first.
    case inOtherParent(String)
    /// Already a sub-series of this one — listed, not choosable.
    case alreadyHere(String)
}

/// One series the "Add sub-series" sheet offers.
struct SubSeriesCandidateItem: Identifiable, Hashable {
    let id: String
    let name: String
    let coverPath: String?
    let bookCount: Int
    let spot: SubSeriesSpot

    var isSelectable: Bool {
        if case .alreadyHere = spot { return false }
        return true
    }

    /// "Top level · 3 books", "In Discworld · moves it here", "Already in Cosmere".
    var meta: String {
        switch spot {
        case .topLevel:
            "\(String(localized: "series.top_level")) · \(SeriesHierarchyText.books(bookCount))"
        case .inOtherParent(let parent):
            String(format: String(localized: "series.picker_moves_here"), parent)
        case .alreadyHere(let parent):
            String(format: String(localized: "series.picker_already_in"), parent)
        }
    }
}

extension SubSeriesCandidateItem {
    /// [pageName] names the series being added to: an ALREADY_HERE row reads "Already in {pageName}".
    init(_ candidate: SubSeriesCandidateUi, pageName: String) {
        let spot: SubSeriesSpot =
            switch candidate.placement {
            case .topLevel: .topLevel
            case .inOtherParent: .inOtherParent(candidate.currentParentName ?? "")
            case .alreadyHere: .alreadyHere(pageName)
            }
        self.init(
            id: candidate.id,
            name: candidate.name,
            coverPath: candidate.coverPath,
            bookCount: Int(candidate.bookCount),
            spot: spot
        )
    }
}

/// A series that already has the name typed into a "new series" dialog.
struct ExistingSeriesItem: Equatable {
    let id: String
    let name: String
    /// Whether the dialog may offer it instead — false when using it would loop or change nothing.
    let isSelectable: Bool
}

/// A "new series" dialog being filled in.
struct NewSeriesDraftItem: Equatable {
    let name: String
    let existing: ExistingSeriesItem?
    let canCreate: Bool
}

extension NewSeriesDraftItem {
    init(_ draft: NewSeriesDraft) {
        self.init(
            name: draft.name,
            existing: draft.existing.map { ExistingSeriesItem(id: $0.id, name: $0.name, isSelectable: $0.isSelectable) },
            canCreate: draft.canCreate
        )
    }
}

/// A move the sheet asks about first.
struct PendingMoveItem: Equatable {
    let seriesId: String
    /// "Move City Watch out of Discworld into Cosmere?"
    let title: String
}

/// Everything the "Add sub-series" sheet shows.
struct AddSubSeriesSheetModel: Equatable {
    var isVisible = false
    var parentName = ""
    var query = ""
    var candidates: [SubSeriesCandidateItem] = []
    var pendingMove: PendingMoveItem?
    var newSeries: NewSeriesDraftItem?
    var isBusy = false

    var title: String { SeriesHierarchyText.addSubSeriesTitle(parent: parentName) }
}

extension AddSubSeriesSheetModel {
    /// Closed is the default model (only `isBusy` carries over); Open maps every field.
    init(_ state: AddSubSeriesUiState) {
        switch state.sealedType() {
        case .closed(let closedType):
            self.init(isBusy: closedType.value.isBusy)
        case .open(let openType):
            self.init(open: openType.value)
        }
    }

    /// Whether the server refused the last change. The refusal is already on screen — the shared
    /// error bus reaches `GlobalErrorObserver`'s alert — so a host only acknowledges it.
    static func hasError(_ state: AddSubSeriesUiState) -> Bool {
        switch state.sealedType() {
        case .closed(let closedType): closedType.value.error != nil
        case .open(let openType): openType.value.error != nil
        }
    }

    init(open state: AddSubSeriesUiStateOpen) {
        let parent = state.parentName
        self.init(
            isVisible: true,
            parentName: parent,
            query: state.query,
            candidates: state.candidates.map { SubSeriesCandidateItem($0, pageName: parent) },
            pendingMove: state.pendingMove.map { move in
                PendingMoveItem(
                    seriesId: move.seriesId,
                    title: SeriesHierarchyText.moveConfirmTitle(
                        series: move.seriesName,
                        from: move.fromParentName,
                        into: move.toParentName
                    )
                )
            },
            newSeries: state.newSeries.map(NewSeriesDraftItem.init),
            isBusy: state.isBusy
        )
    }
}

/// What the reader does in the "Add sub-series" sheet, as a native value. Each observer that hosts
/// the sheet turns one into the shared `AddSubSeriesEvent` with `kotlinEvent`.
enum AddSubSeriesAction: Equatable {
    case opened
    case dismissed
    case queryChanged(String)
    case chosen(String)
    case moveConfirmed
    case moveCancelled
    case newSeriesStarted
    case newSeriesNameChanged(String)
    case newSeriesDismissed
    case newSeriesConfirmed
    case errorDismissed

    var kotlinEvent: any AddSubSeriesEvent {
        switch self {
        case .opened: AddSubSeriesEventOpened.shared
        case .dismissed: AddSubSeriesEventDismissed.shared
        case .queryChanged(let query): AddSubSeriesEventQueryChanged(query: query)
        case .chosen(let id): AddSubSeriesEventChosen(seriesId: id)
        case .moveConfirmed: AddSubSeriesEventMoveConfirmed.shared
        case .moveCancelled: AddSubSeriesEventMoveCancelled.shared
        case .newSeriesStarted: AddSubSeriesEventNewSeriesStarted.shared
        case .newSeriesNameChanged(let name): AddSubSeriesEventNewSeriesNameChanged(name: name)
        case .newSeriesDismissed: AddSubSeriesEventNewSeriesDismissed.shared
        case .newSeriesConfirmed: AddSubSeriesEventNewSeriesConfirmed.shared
        case .errorDismissed: AddSubSeriesEventErrorDismissed.shared
        }
    }
}
