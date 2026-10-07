import Foundation
import Shared

// Native value types for Match details, mapped once at the observer boundary (iosApp rule 8): the
// views, their `ForEach`es and the accessibility harness only ever see these, never a bridged Kotlin
// object. Everything a row says is already a sentence here, so a view is layout and nothing else.

/// One store a person can search instead, for this search only.
typealias MatchStoreChoice = MetadataRegionOption

/// The Your copy strip at the top of Find: what this device already knows about the book.
struct MatchYourCopy: Equatable {
    let title: String
    let coverPath: String?
    let coverHash: String?
    /// "16h 10m · Ray Porter · 36 chapters", or nil when nothing is known.
    let detailLine: String?
    /// "Started from your Audible link, then title, author and length.", or nil before any search answered.
    let stepsLine: String?
    /// Compare's "Your copy" column.
    let compare: MatchCompareValues
}

/// The values Compare puts side by side. Missing values are nil and read "Not listed".
struct MatchCompareValues: Equatable {
    let length: String?
    let narrator: String?
    let chapters: String?
    let year: String?
    let format: String?
    let store: String?
    let foundIn: String?
}

/// "Audible store: United States" and the stores it can switch to.
struct MatchStoreMenu: Equatable {
    let title: String
    let selected: MatchStoreChoice
    let choices: [MatchStoreChoice]
}

/// One reason a candidate matches (or doesn't) your copy.
struct MatchReasonLine: Equatable, Hashable {
    let text: String
}

/// One Find candidate, as a row says it.
struct MatchCandidateRow: Identifiable, Equatable {
    let id: String
    let title: String
    let coverURL: String?
    let isStrong: Bool
    let isBest: Bool
    let isCurrentLink: Bool
    /// "Ray Porter · 16h 10m · 2021 · Unabridged".
    let metadataLine: String
    let reasons: [MatchReasonLine]
    /// "Audible · Hardcover · iTunes".
    let foundInLine: String
    /// "Audible, Hardcover and iTunes" — for sentences ("Found in …") and accessible names.
    let foundInList: String
    let compare: MatchCompareValues
}

/// "Hardcover didn't answer, so these results are from Audible and iTunes." with Retry.
struct MatchPartialBanner: Equatable {
    let message: String
    let retryTitle: String
}

/// Candidates to choose from.
struct MatchResults: Equatable {
    let strong: [MatchCandidateRow]
    let maybe: [MatchCandidateRow]
    let partial: MatchPartialBanner?
    let pickedId: String?

    var all: [MatchCandidateRow] { strong + maybe }
}

/// What a failure offers as a way forward.
enum MatchFailureAction: Equatable, Identifiable {
    case retry(title: String)
    /// A rate limit's Retry, disabled until the countdown reaches zero.
    case retryCountdown(title: String, isEnabled: Bool)
    case tryStore(title: String, store: MatchStoreChoice)
    case searchByTitle(title: String)

    var id: String {
        switch self {
        case .retry: "retry"
        case .retryCountdown: "retry-countdown"
        case .tryStore(_, let store): "store-\(store.id)"
        case .searchByTitle: "search-by-title"
        }
    }

    var title: String {
        switch self {
        case .retry(let title), .retryCountdown(let title, _), .tryStore(let title, _), .searchByTitle(let title):
            title
        }
    }

    var isEnabled: Bool {
        if case .retryCountdown(_, let isEnabled) = self { return isEnabled }
        return true
    }
}

/// Why Find has nothing to show, and what to do about it.
struct MatchFailure: Equatable {
    let systemImage: String
    let title: String
    let message: String
    let actions: [MatchFailureAction]
}

/// The Find step.
enum MatchFindPhase: Equatable {
    /// A search is running; the last results stay on screen.
    case searching(previous: MatchResults?)
    case results(MatchResults)
    case failed(MatchFailure)

    /// The rows on screen in this phase.
    var results: MatchResults? {
        switch self {
        case .searching(let previous): previous
        case .results(let results): results
        case .failed: nil
        }
    }
}

/// Everything Find shows.
struct MatchFind: Equatable {
    let query: String
    let yourCopy: MatchYourCopy?
    let store: MatchStoreMenu?
    let phase: MatchFindPhase

    static let initial = MatchFind(query: "", yourCopy: nil, store: nil, phase: .searching(previous: nil))
}

// MARK: - Review

/// The sections of Review, in canvas order. Each is a scroll target for What will change.
enum MatchReviewSection: String, CaseIterable, Hashable {
    case cover, changes, fillsGap, youEdited, labels, chapterNames, alreadySame
}

/// One count in What will change, jumping to its section.
struct MatchSummaryItem: Identifiable, Equatable {
    let section: MatchReviewSection
    /// "5", or "Cover".
    let value: String
    /// "changes", "from Hardcover".
    let caption: String

    var id: MatchReviewSection { section }
}

/// A source switch segment: one option, or Keep yours.
enum MatchSourceSelection: Hashable {
    case option(String)
    case keepYours
}

/// One segment of a field's source switch.
struct MatchSourceSegment: Identifiable, Equatable {
    let selection: MatchSourceSelection
    let title: String

    var id: MatchSourceSelection { selection }
}

/// How a source switch is drawn: segments up to four, a menu beyond (HIG, Segmented controls).
enum MatchSourceSwitchStyle: Equatable {
    case none, segmented, menu
}

/// One reviewable field: Yours → Proposed, a tick, and a source switch.
struct MatchFieldRow: Identifiable, Equatable {
    let field: BookField
    let name: String
    /// "from Audible".
    let proposedFrom: String
    let yours: String?
    let proposed: String
    let isLongText: Bool
    let isTicked: Bool
    let isEdited: Bool
    /// "Edited by you, 12 Sep. Kept unless you tick it."
    let editedNote: String?
    let segments: [MatchSourceSegment]
    let selectedSegment: MatchSourceSelection
    let switchStyle: MatchSourceSwitchStyle
    /// The tick's accessible name: "Description, proposed from Audible, changes yours".
    let tickLabel: String
    /// The values, read in full: "Title. Yours: …. Proposed from Audible: …."
    let valuesLabel: String

    var id: Int32 { field.rawValue }
}

/// One cover tile: Keep current, or a candidate.
struct MatchCoverTile: Identifiable, Equatable {
    /// "keep" for Keep current, else the candidate's option id.
    let id: String
    let title: String
    let detail: String?
    let url: String?
    let isKeepCurrent: Bool
    let isSelected: Bool
    let accessibilityLabel: String
}

/// The cover radio group.
struct MatchCoverSection: Equatable {
    /// "Hardcover", the source of the cover Apply writes, or nil for Keep current.
    let chosenSource: String?
    let tiles: [MatchCoverTile]
    let bookCoverPath: String?
    let bookCoverHash: String?
}

/// One of your labels: kept, or removed with its ×.
struct MatchYourLabel: Identifiable, Equatable {
    let label: String
    let removed: Bool
    var id: String { label }
}

/// One suggested label with where it came from.
struct MatchSuggestion: Identifiable, Equatable {
    let label: String
    /// "Audible", "Audible and Hardcover".
    let sources: String
    let selected: Bool
    /// "Thriller, from Hardcover".
    let accessibilityLabel: String
    var id: String { label }
}

/// Genres or moods: yours, kept, and the suggestions.
struct MatchLabelGroup: Identifiable, Equatable {
    let kind: LabelKind
    let title: String
    let yours: [MatchYourLabel]
    let suggested: [MatchSuggestion]
    var id: String { title }
}

/// One chapter whose name differs.
struct MatchChapterRow: Identifiable, Equatable {
    let ordinal: Int32
    let yours: String
    let theirs: String
    let selected: Bool
    let accessibilityLabel: String
    var id: Int32 { ordinal }
}

/// The chapter names section.
enum MatchChapterSection: Equatable {
    /// A different edition: shown, never applied.
    case mismatch(message: String)
    case available(summary: String, included: Bool, rows: [MatchChapterRow])
}

/// The sticky Apply bar.
struct MatchApplyBar: Equatable {
    /// "5 fields · cover · 16 chapter names".
    let summary: String
    let canApply: Bool
    let applying: Bool
    /// "<message> Nothing was changed."
    let error: String?
}

/// Everything Review shows for one candidate.
struct MatchReview: Equatable {
    let candidate: MatchCandidateRow
    /// "Found in Audible, Hardcover and iTunes".
    let foundInSentence: String
    let summary: [MatchSummaryItem]
    let cover: MatchCoverSection?
    let changes: [MatchFieldRow]
    let fillsGap: [MatchFieldRow]
    let youEdited: [MatchFieldRow]
    let labels: [MatchLabelGroup]
    let chapters: MatchChapterSection?
    /// "6 fields already match: Authors, Narrators, …", or nil when nothing does.
    let alreadySame: String?
    let applyBar: MatchApplyBar
}

/// The Review step.
enum MatchReviewPhase: Equatable {
    case noneChosen
    case loading(candidateId: String, title: String)
    case failed(candidateId: String, title: String, message: String)
    case ready(MatchReview)

    /// The candidate this phase is about, if any.
    var candidateId: String? {
        switch self {
        case .noneChosen: nil
        case .loading(let id, _), .failed(let id, _, _): id
        case .ready(let review): review.candidate.id
        }
    }
}

// MARK: - Receipt

/// The receipt on Book Detail.
enum MatchReceiptPhase: Equatable {
    case none
    case shown(MatchReceiptModel)
    case undone
    case expired
}

/// "Changed 5 fields, cover from Hardcover, 16 chapter names", Undo and See What Changed.
struct MatchReceiptModel: Equatable {
    let id: String
    let sentence: String
    let changes: [String]
    let canUndo: Bool
    let undoing: Bool
    let undoError: String?
}
