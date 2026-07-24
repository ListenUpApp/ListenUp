import Testing
@testable import ListenUp

@Suite("AuthStateKind")
struct AuthStateKindTests {
    // AuthStateObserver itself needs a live KMP AuthSession; its construction is
    // reviewed against the contract. AuthStateKind — the flattened enum the UI
    // switches on — is pure and verified here.
    @Test func allEightCasesAreDistinct() {
        let all: [AuthStateKind] = [
            .initializing, .needsServerUrl, .checkingServer,
            .needsSetup, .needsLogin, .pendingApproval, .authenticated, .sessionLapsed
        ]
        #expect(Set(all).count == 8)
    }

    @Test func equatableHoldsForMatchingCases() {
        #expect(AuthStateKind.authenticated == AuthStateKind.authenticated)
        #expect(AuthStateKind.needsLogin != AuthStateKind.needsSetup)
    }
}
