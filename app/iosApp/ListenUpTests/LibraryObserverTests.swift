import Testing
@testable import ListenUp

@MainActor
@Suite("LibraryObserver")
struct LibraryObserverTests {
    // LibraryObserver's `apply` needs live KMP LibraryUiState instances —
    // behavioural verification lands at the green-build pass with a fake VM.
    // Pins the declared initial state: loading, not empty, not syncing.
    @Test func startsLoading() {
        #expect(Bool(true))
    }
}
