import Foundation
@preconcurrency import Shared

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

/// Hits split into the four render sections, de-duplicated by id within each, with
/// the repository's relevance order preserved. Pure so the grouping is unit-tested
/// rather than buried in the observer.
struct SearchHitGroups: Equatable {
    var books: [SearchHit] = []
    var people: [SearchHit] = []
    var series: [SearchHit] = []
    var tags: [SearchHit] = []

    var isEmpty: Bool {
        books.isEmpty && people.isEmpty && series.isEmpty && tags.isEmpty
    }

    static func group(_ hits: [SearchHit]) -> SearchHitGroups {
        var groups = SearchHitGroups()
        var seen: Set<String> = []
        for hit in hits {
            // De-dupe across the whole result by id: the same entity must not appear
            // twice even if the backend echoes it under multiple rows.
            guard seen.insert(hit.id).inserted else { continue }
            switch hit.type {
            case .book: groups.books.append(hit)
            case .contributor: groups.people.append(hit)
            case .series: groups.series.append(hit)
            case .tag: groups.tags.append(hit)
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
    let hits: [SearchHit]
    let totalCount: Int
    let seeAllType: SearchSeeAllType?

    /// Cap `hits` to `limit`; expose `seeAllType` when the full list is longer than the cap.
    init(_ hits: [SearchHit], cap: Int, type: SearchSeeAllType) {
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
    case tag(id: String)
}

/// The render phase of the search screen, flattened from the shared `SearchUiState`.
enum SearchPhase: Equatable {
    case idle
    case searching
    case results
    case empty
    case error(String)
}
