import Testing
@preconcurrency import Shared
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

    // MARK: - Grouping

    @Test func groupsSplitHitsByType() {
        let hits = [
            hit("b1", .book),
            hit("p1", .contributor),
            hit("s1", .series),
            hit("t1", .tag),
            hit("b2", .book)
        ]
        let groups = SearchHitGroups.group(hits)
        #expect(groups.books.map(\.id) == ["b1", "b2"])
        #expect(groups.people.map(\.id) == ["p1"])
        #expect(groups.series.map(\.id) == ["s1"])
        #expect(groups.tags.map(\.id) == ["t1"])
    }

    @Test func groupingDeDupesById() {
        let groups = SearchHitGroups.group([hit("b1", .book), hit("b1", .book), hit("b2", .book)])
        #expect(groups.books.map(\.id) == ["b1", "b2"])
    }

    @Test func groupingPreservesRelevanceOrder() {
        let groups = SearchHitGroups.group([hit("b3", .book), hit("b1", .book), hit("b2", .book)])
        #expect(groups.books.map(\.id) == ["b3", "b1", "b2"])
    }

    @Test func emptyHitsProduceEmptyGroups() {
        #expect(SearchHitGroups.group([]).isEmpty)
    }

    // MARK: - Helpers

    private func hit(_ id: String, _ type: SearchHitType) -> SearchHit {
        SearchHit(
            id: id,
            type: type,
            name: "Name \(id)",
            subtitle: nil,
            author: nil,
            narrator: nil,
            seriesName: nil,
            duration: nil,
            bookCount: nil,
            genreSlugs: nil,
            tags: nil,
            coverPath: nil,
            score: 0,
            highlight: nil
        )
    }
}
