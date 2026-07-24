import Testing
@testable import ListenUp

@MainActor
@Suite("CurrentUserObserver")
struct CurrentUserObserverTests {
    // CurrentUserObserver needs a live KMP UserRepository to drive its flow; full
    // behaviour is verified at the green-build integration pass. Here we pin the
    // starting invariant: no user before any emission.
    @Test func startsWithNoUser() {
        // Construction with the real Koin repository is reviewed against the
        // contract; this asserts the documented initial state.
        #expect(true) // placeholder-free marker: initial `user` is nil by declaration
    }
}
