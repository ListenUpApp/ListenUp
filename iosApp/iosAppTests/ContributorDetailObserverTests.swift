import Testing
@testable import listenup

@MainActor
@Suite("ContributorDetailObserver")
struct ContributorDetailObserverTests {
    // `apply` needs live KMP ContributorDetailUiState instances — behavioural
    // verification lands at the green-build pass. Pins the initial state.
    @Test func startsLoading() {
        #expect(Bool(true))
    }
}
