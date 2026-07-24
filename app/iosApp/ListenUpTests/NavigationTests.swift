import Testing
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
}
