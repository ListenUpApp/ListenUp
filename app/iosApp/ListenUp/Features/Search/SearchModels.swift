import Foundation
import Shared

/// Single-select search scope, surfaced as `.searchScopes` segments above the results.
///
/// The shared `SearchViewModel` models filters as an additive `Set<SearchHitType>`
/// (multi-select). iOS's search UI is single-select, so a `SearchScope` is the iOS
/// projection of that set: an empty set is "All", and exactly one type maps to that
/// scope. Any unexpected multi-type set collapses to `.all` — the safe superset.
enum SearchScope: Hashable, CaseIterable {
    case all
    case books
    case people
    case series
    case tags

    /// The single hit type this scope filters to, or `nil` for `.all` (no filter).
    var hitType: SearchHitType? {
        switch self {
        case .all: nil
        case .books: .book
        case .people: .contributor
        case .series: .series
        case .tags: .tag
        }
    }

    /// The set of types this scope represents in the shared VM (`.all` → empty set).
    var selectedTypes: Set<SearchHitType> {
        guard let hitType else { return [] }
        return [hitType]
    }

    /// Project the VM's `selectedTypes` set onto a single-select scope. Empty → `.all`,
    /// a single type → its scope, anything else → `.all` (the safe superset).
    static func from(selectedTypes: Set<SearchHitType>) -> SearchScope {
        guard selectedTypes.count == 1, let only = selectedTypes.first else { return .all }
        return SearchScope.allCases.first { $0.hitType == only } ?? .all
    }

    /// The symmetric difference between the current VM types and this scope's target
    /// types — i.e. exactly the `toggleTypeFilter(_:)` calls needed to move the VM
    /// from `current` to this scope, since the VM exposes only an additive toggle.
    func toggles(from current: Set<SearchHitType>) -> [SearchHitType] {
        Array(current.symmetricDifference(selectedTypes))
    }
}

/// The kind of search hit, as a native Swift enum so rows carry no bridged Kotlin type.
enum SearchRowKind: Equatable, Hashable {
    case book
    case person
    case series
    case tag

    /// The shared `SearchHitType` this kind maps to (for navigation back through the VM).
    var hitType: SearchHitType {
        switch self {
        case .book: .book
        case .person: .contributor
        case .series: .series
        case .tag: .tag
        }
    }
}

/// A native, value-typed projection of the bridged Kotlin `SearchHit` for SwiftUI lists.
///
/// **Why (performance):** `SearchHit` is a Swift Export-bridged Kotlin object; feeding it straight into a
/// `ForEach`/`List` makes every diff/layout pass re-read its properties across the Swift Export boundary
/// (`toKStringFromUtf16` per access). Search is query-unbounded, so that re-bridging is the same
/// main-thread hazard that hung the books grid. `SearchRow` snapshots the rendered fields — and the
/// per-kind subtitle, computed ONCE — into plain Swift values at the observer boundary, so SwiftUI
/// diffs cheap structs. Mirrors `BookRow`/`SeriesRow`/`ContributorRow`.
struct SearchRow: Identifiable, Equatable, Hashable {
    let id: String
    let kind: SearchRowKind
    let name: String
    /// Precomputed per-kind detail line: book "author · duration", person "role · N books",
    /// series "author · N books"; nil for tags.
    let subtitle: String?
    /// Author alone — the cover cards' second line shows just this (the list rows use `subtitle`).
    let author: String?
    let coverPath: String?
    let coverHash: String?

    init(id: String, kind: SearchRowKind, name: String, subtitle: String?, author: String?, coverPath: String?, coverHash: String?) {
        self.id = id
        self.kind = kind
        self.name = name
        self.subtitle = subtitle
        self.author = author
        self.coverPath = coverPath
        self.coverHash = coverHash
    }

    /// Snapshot a Kotlin `SearchHit` into native values once — reads each bridged property (and the
    /// `formatDuration()`/`bookCount` calls) exactly once; SwiftUI never reads them again.
    init(_ hit: SearchHit) {
        self.id = hit.id
        self.name = hit.name
        self.author = hit.author?.nilIfEmpty
        self.coverPath = hit.coverPath
        self.coverHash = hit.coverHash
        switch hit.type {
        case .book:
            self.kind = .book
            self.subtitle = [hit.author, hit.formatDuration()]
                .compactMap { $0?.nilIfEmpty }
                .joined(separator: " · ")
                .nilIfEmpty
        case .contributor:
            self.kind = .person
            self.subtitle = SearchRow.detailLine(lead: hit.subtitle, count: hit.bookCount.map { Int($0) })
        case .series:
            self.kind = .series
            self.subtitle = SearchRow.detailLine(lead: hit.author, count: hit.bookCount.map { Int($0) })
        case .tag:
            self.kind = .tag
            self.subtitle = nil
        }
    }

    /// "lead · N books" — the shared shape for the person (role) and series (author) subtitles.
    private static func detailLine(lead: String?, count: Int?) -> String? {
        var parts: [String] = []
        if let lead = lead?.nilIfEmpty { parts.append(lead) }
        if let count {
            parts.append(String(format: String(localized: "search.count_books"), String(count)))
        }
        return parts.joined(separator: " · ").nilIfEmpty
    }
}

/// Rows split into the four render sections, de-duplicated by id within each, with
/// the repository's relevance order preserved. Pure so the grouping is unit-tested
/// rather than buried in the observer.
struct SearchHitGroups: Equatable {
    var books: [SearchRow] = []
    var people: [SearchRow] = []
    var series: [SearchRow] = []
    var tags: [SearchRow] = []

    var isEmpty: Bool {
        books.isEmpty && people.isEmpty && series.isEmpty && tags.isEmpty
    }

    static func group(_ rows: [SearchRow]) -> SearchHitGroups {
        var groups = SearchHitGroups()
        var seen: Set<String> = []
        for row in rows {
            // De-dupe across the whole result by id: the same entity must not appear
            // twice even if the backend echoes it under multiple rows.
            guard seen.insert(row.id).inserted else { continue }
            switch row.kind {
            case .book: groups.books.append(row)
            case .person: groups.people.append(row)
            case .series: groups.series.append(row)
            case .tag: groups.tags.append(row)
            }
        }
        return groups
    }
}

/// Per-type display caps for the main results, read from the shared `SearchResultCaps` so
/// iOS and Android stay in lockstep. Tags are intentionally uncapped (they render as inline
/// pills). A group whose hit count exceeds its cap shows a "See all" affordance that pushes
/// the full single-type page.
enum SearchDisplayCap {
    static let books = Int(SearchResultCaps.shared.BOOK)
    static let people = Int(SearchResultCaps.shared.CONTRIBUTOR)
    static let series = Int(SearchResultCaps.shared.SERIES)
}

/// A single capped result group ready to render: the visible prefix (`hits`) and the
/// `seeAllType` that owns the full page — non-`nil` only when the group overflowed its cap.
/// Pure value so the cap/overflow decision is unit-tested rather than buried in the layout.
struct CappedGroup: Equatable {
    let hits: [SearchRow]
    let totalCount: Int
    let seeAllType: SearchSeeAllType?

    /// Cap `hits` to `limit`; expose `seeAllType` when the full list is longer than the cap.
    init(_ hits: [SearchRow], cap: Int, type: SearchSeeAllType) {
        self.totalCount = hits.count
        self.hits = Array(hits.prefix(cap))
        self.seeAllType = hits.count > cap ? type : nil
    }
}

extension SearchHitGroups {
    var cappedBooks: CappedGroup { CappedGroup(books, cap: SearchDisplayCap.books, type: .book) }
    var cappedPeople: CappedGroup { CappedGroup(people, cap: SearchDisplayCap.people, type: .contributor) }
    var cappedSeries: CappedGroup { CappedGroup(series, cap: SearchDisplayCap.series, type: .series) }
}

/// A resolved navigation target from a tapped search hit.
enum SearchRoute: Hashable {
    case book(id: String)
    case contributor(id: String)
    case series(id: String)
    case tag(id: String, name: String)
}

/// The render phase of the search screen, flattened from the shared `SearchUiState`.
enum SearchPhase: Equatable {
    case idle
    case searching
    case results
    case empty
    case error(String)
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}
