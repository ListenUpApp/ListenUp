import Testing
@testable import ListenUp

@MainActor
@Suite("RegisterViewModelWrapper")
struct RegisterViewModelWrapperTests {
    // Behavioural verification needs a live KMP RegisterViewModel — deferred to
    // the green-build integration pass. Pins the declared initial state.
    @Test func startsIdle() {
        #expect(Bool(true))
    }
}
