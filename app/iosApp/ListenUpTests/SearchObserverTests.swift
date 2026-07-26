import Testing
import Shared
@testable import ListenUp

/// Pure-mapping coverage for the search observer's two seams: the single-select
/// scope ← `selectedTypeNames` projection, and the de-duplicating hit grouping.
///
/// The projection is name-based on purpose: reading the VM's `Set<SearchHitType>` across
/// Swift Export traps on element cast. That trap is a *runtime bridge* failure, so nothing
/// here can catch it — these tests pin the shape of the safe path, and the guarantee that
/// the unsafe one stays unused lives in `NoBridgedEnumCollectionsInUiStateRule`.
///
/// `SearchObserver.apply`'s flatten of the sealed `SearchUiState` (including the
/// `.tooShort` phase added for the trigram-index minimum-query-length floor) can't be
/// exercised here: SKIE bridges `SearchUiState` as a sealed protocol whose cases aren't
/// constructible from Swift, so that `onEnum` mapping — including the new `TooShort` →
/// `.tooShort` arm landing before any empty-collapse logic — is proven at the
/// green-build pass (the app target's exhaustive `switch` compiling). What *is* pure and
/// constructible is the mirrored `minSearchQueryLength` floor, pinned below.
struct SearchObserverTests {
    // MARK: - Name projection → scope (the bridge-safe read path)

    /// The shared VM hands iOS `selectedTypeNames`, not `selectedTypes`: bridging the Kotlin
    /// `Set<SearchHitType>` traps on element cast ("Could not cast value of type
    /// 'Swift.AnyHashable' to …SearchHitType") the moment a scope is selected.
    @Test func noTypesMapToAll() {
        #expect(SearchScope.from(typeNames: []) == .all)
    }

    @Test func singleTypeNameMapsToItsScope() {
        #expect(SearchScope.from(typeNames: ["BOOK"]) == .books)
        #expect(SearchScope.from(typeNames: ["CONTRIBUTOR"]) == .people)
        #expect(SearchScope.from(typeNames: ["SERIES"]) == .series)
        #expect(SearchScope.from(typeNames: ["TAG"]) == .tags)
    }

    /// Compose's chips are multi-select, so the shared state can legitimately hold several
    /// types that iOS's one-of-N control cannot express. `.all` is the safe superset.
    @Test func multipleTypeNamesCollapseToAll() {
        #expect(SearchScope.from(typeNames: ["BOOK", "SERIES"]) == .all)
        #expect(SearchScope.from(typeNames: ["BOOK", "TAG"]) == .all)
    }

    /// Every scope survives the write→read round trip: `hitType` is what iOS sends to
    /// `setTypeFilter`, and its name is what comes back in `selectedTypeNames`.
    @Test func everyScopeRoundTripsThroughTheSharedProjection() {
        for scope in SearchScope.allCases {
            let names = scope.hitType.map { [$0.description] } ?? []
            #expect(SearchScope.from(typeNames: names) == scope)
        }
    }

    /// Pins the exact strings the shared `selectedTypeNames` projection emits (Kotlin's
    /// `SearchHitType.entries.map { it.name }`) against Swift Export's generated
    /// `LosslessStringConvertible` round-trip. A rename on either side breaks this, not the app.
    @Test func everyHitTypeRoundTripsThroughItsName() {
        for type in SearchHitType.allCases {
            #expect(SearchHitType(type.description) == type)
        }
        #expect(SearchHitType.book.description == "BOOK")
        #expect(SearchHitType.contributor.description == "CONTRIBUTOR")
        #expect(SearchHitType.series.description == "SERIES")
        #expect(SearchHitType.tag.description == "TAG")
    }

    /// An unknown name is dropped rather than trapping — a shared enum can gain a case that
    /// this build predates. Matching is exact, so casing is not a near-miss.
    @Test func unknownNamesAreDropped() {
        #expect(SearchScope.from(typeNames: ["BOOK", "PODCAST"]) == .books)
        #expect(SearchScope.from(typeNames: ["book"]) == .all)
        #expect(SearchScope.from(typeNames: ["PODCAST"]) == .all)
    }

    // MARK: - Grouping (over native SearchRow)

    @Test func groupsSplitRowsByKind() {
        let rows = [
            row("b1", .book),
            row("p1", .person),
            row("s1", .series),
            row("t1", .tag),
            row("b2", .book)
        ]
        let groups = SearchHitGroups.group(rows)
        #expect(groups.books.map(\.id) == ["b1", "b2"])
        #expect(groups.people.map(\.id) == ["p1"])
        #expect(groups.series.map(\.id) == ["s1"])
        #expect(groups.tags.map(\.id) == ["t1"])
    }

    @Test func groupingDeDupesById() {
        let groups = SearchHitGroups.group([row("b1", .book), row("b1", .book), row("b2", .book)])
        #expect(groups.books.map(\.id) == ["b1", "b2"])
    }

    @Test func groupingPreservesRelevanceOrder() {
        let groups = SearchHitGroups.group([row("b3", .book), row("b1", .book), row("b2", .book)])
        #expect(groups.books.map(\.id) == ["b3", "b1", "b2"])
    }

    @Test func emptyRowsProduceEmptyGroups() {
        #expect(SearchHitGroups.group([]).isEmpty)
    }

    // MARK: - Display caps (mirror the shared SearchResultCaps)

    @Test func displayCapsComeFromSharedSource() {
        #expect(SearchDisplayCap.books == Int(SearchResultCaps.shared.BOOK))
        #expect(SearchDisplayCap.people == Int(SearchResultCaps.shared.CONTRIBUTOR))
        #expect(SearchDisplayCap.series == Int(SearchResultCaps.shared.SERIES))
    }

    @Test func groupUnderCapShowsNoSeeAll() {
        let rows = [row("b1", .book), row("b2", .book)]
        let capped = CappedGroup(rows, cap: SearchDisplayCap.books, type: .book)
        #expect(capped.hits.count == 2)
        #expect(capped.totalCount == 2)
        #expect(capped.seeAllType == nil)
    }

    @Test func groupOverCapTruncatesAndOffersSeeAll() {
        let rows = (1...10).map { row("b\($0)", .book) }
        let capped = CappedGroup(rows, cap: SearchDisplayCap.books, type: .book)
        #expect(capped.hits.count == SearchDisplayCap.books)
        #expect(capped.totalCount == 10)
        #expect(capped.seeAllType == .book)
    }

    @Test func groupExactlyAtCapShowsNoSeeAll() {
        let rows = (1...SearchDisplayCap.series).map { row("s\($0)", .series) }
        let capped = CappedGroup(rows, cap: SearchDisplayCap.series, type: .series)
        #expect(capped.hits.count == SearchDisplayCap.series)
        #expect(capped.seeAllType == nil)
    }

    @Test func cappedGroupAccessorsPreserveOrderAndType() {
        let books = (1...6).map { row("b\($0)", .book) }
        let people = (1...6).map { row("p\($0)", .person) }
        let groups = SearchHitGroups.group(books + people)
        #expect(groups.cappedBooks.hits.map(\.id) == ["b1", "b2", "b3", "b4"])
        #expect(groups.cappedBooks.seeAllType == .book)
        #expect(groups.cappedPeople.hits.map(\.id) == ["p1", "p2", "p3"])
        #expect(groups.cappedPeople.seeAllType == .contributor)
    }

    // MARK: - See-all type ↔ shared hit type

    @Test func seeAllTypeMapsToSharedHitType() {
        #expect(SearchSeeAllType.book.hitType == .book)
        #expect(SearchSeeAllType.contributor.hitType == .contributor)
        #expect(SearchSeeAllType.series.hitType == .series)
    }

    // MARK: - Minimum query length floor

    /// Mirrors `MIN_SEARCH_QUERY_LENGTH` (`client/.../domain/model/Search.kt`). Unlike
    /// `SearchDisplayCap`, this can't cross-check against the shared source directly — the
    /// Kotlin constant is a top-level `const val`, not an exported type/object, so it isn't
    /// part of the Swift Export surface (see `SearchModels.swift`). This pins the mirrored
    /// value so a change on either side without the other shows up as a failing test rather
    /// than silent drift.
    @Test func minSearchQueryLengthMatchesTheSharedFloor() {
        #expect(minSearchQueryLength == 3)
    }

    @Test func tooShortPhaseIsDistinctFromOtherPhases() {
        #expect(SearchPhase.tooShort != .idle)
        #expect(SearchPhase.tooShort != .searching)
        #expect(SearchPhase.tooShort != .empty)
        #expect(SearchPhase.tooShort != .results)
    }

    @Test func seeAllTooShortPhaseIsDistinctFromOtherPhases() {
        #expect(SeeAllPhase.tooShort != .idle)
        #expect(SeeAllPhase.tooShort != .loading)
        #expect(SeeAllPhase.tooShort != .empty)
        #expect(SeeAllPhase.tooShort != .results([]))
    }

    // MARK: - Helpers

    private func row(_ id: String, _ kind: SearchRowKind) -> SearchRow {
        SearchRow(id: id, kind: kind, name: "Name \(id)", subtitle: nil, author: nil, coverPath: nil, coverHash: nil)
    }
}
