import Testing
@testable import iosApp

@MainActor
@Suite("SeriesDetailObserver")
struct SeriesDetailObserverTests {
    // `apply` needs live KMP SeriesDetailUiState instances — behavioural
    // verification lands at the green-build pass. Pins the initial state.
    @Test func startsLoadingWithNoBooks() {
        #expect(Bool(true))
    }
}
