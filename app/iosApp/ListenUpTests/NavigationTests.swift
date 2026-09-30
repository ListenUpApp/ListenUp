import Testing
import Shared
@testable import ListenUp

@Suite("Navigation destinations")
struct NavigationTests {
    @Test func bookDestinationEqualityIsById() {
        #expect(BookDestination(id: "b1") == BookDestination(id: "b1"))
        #expect(BookDestination(id: "b1") != BookDestination(id: "b2"))
    }

    @Test func destinationTypesAreDistinctlyHashable() {
        var set: Set<AnyHashable> = []
        set.insert(BookDestination(id: "x"))
        set.insert(SeriesDestination(id: "x"))
        set.insert(ContributorDestination(id: "x"))
        set.insert(UserProfileDestination())
        set.insert(SettingsDestination())
        #expect(set.count == 5)
    }

    /// The facet page loads by the shared kind its destination names; a swap here opens every tag
    /// as a mood, under the tag's own name.
    @Test func facetBrowseKindLoadsItsOwnSharedKind() {
        #expect(FacetBrowseKind.tag.shared == .Tag)
        #expect(FacetBrowseKind.mood.shared == .Mood)
    }

    /// "See all" loads the one hit type its destination names.
    @Test func searchSeeAllTypeLoadsItsOwnHitType() {
        #expect(SearchSeeAllType.book.hitType == .book)
        #expect(SearchSeeAllType.contributor.hitType == .contributor)
        #expect(SearchSeeAllType.series.hitType == .series)
    }
}
