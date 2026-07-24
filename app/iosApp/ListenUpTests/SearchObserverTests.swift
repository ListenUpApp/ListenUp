import Testing
import Shared
@testable import ListenUp

/// Pure-mapping coverage for the search observer's two seams: the single-select
/// scope ↔ `Set<SearchHitType>` projection, and the de-duplicating hit grouping.
struct SearchObserverTests {
    // MARK: - Scope ↔ types projection

    @Test func emptyTypesMapToAll() {
        #expect(SearchScope.from(selectedTypes: []) == .all)
    }

    @Test func singleTypeMapsToItsScope() {
        #expect(SearchScope.from(selectedTypes: [.book]) == .books)
        #expect(SearchScope.from(selectedTypes: [.contributor]) == .people)
        #expect(SearchScope.from(selectedTypes: [.series]) == .series)
        #expect(SearchScope.from(selectedTypes: [.tag]) == .tags)
    }

    @Test func multipleTypesCollapseToAll() {
        #expect(SearchScope.from(selectedTypes: [.book, .series]) == .all)
    }

    @Test func scopeRoundTripsThroughItsTypes() {
        for scope in SearchScope.allCases {
            #expect(SearchScope.from(selectedTypes: scope.selectedTypes) == scope)
        }
    }

    @Test func allScopeHasNoFilterTypes() {
        #expect(SearchScope.all.selectedTypes.isEmpty)
        #expect(SearchScope.books.selectedTypes == [.book])
    }

    // MARK: - Toggle deltas (the VM exposes only an additive toggle)

    @Test func selectingBooksFromAllTogglesBookOn() {
        #expect(SearchScope.books.toggles(from: []) == [.book])
    }

    @Test func returningToAllTogglesTheActiveTypeOff() {
        #expect(SearchScope.all.toggles(from: [.book]) == [.book])
    }

    @Test func switchingScopesTogglesBothTypes() {
        let toggles = Set(SearchScope.series.toggles(from: [.book]))
        #expect(toggles == [.book, .series])
    }

    @Test func reselectingTheSameScopeIsANoOp() {
        #expect(SearchScope.books.toggles(from: [.book]).isEmpty)
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

    // MARK: - Helpers

    private func row(_ id: String, _ kind: SearchRowKind) -> SearchRow {
        SearchRow(id: id, kind: kind, name: "Name \(id)", subtitle: nil, author: nil, coverPath: nil, coverHash: nil)
    }
}
