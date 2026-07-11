import Testing
@testable import ListenUp

@Suite("ConnectionHealthKind")
struct ConnectionHealthKindTests {
    // ConnectionHealthObserver itself needs a live KMP ConnectionHealthViewModel; its construction
    // and `onEnum` mapping are reviewed against the contract. ConnectionHealthKind — the flattened
    // enum the banner switches on — is pure and verified here.

    @Test func valuelessCasesAreDistinct() {
        #expect(ConnectionHealthKind.hidden != .unreachable)
        #expect(ConnectionHealthKind.unreachable != .sessionExpired)
        #expect(ConnectionHealthKind.sessionExpired != .hidden)
    }

    @Test func outdatedCarriesVersionsAndEquatesByValue() {
        let a = ConnectionHealthKind.outdated(clientVersion: "0.6.0", serverVersion: "0.7.0")
        let b = ConnectionHealthKind.outdated(clientVersion: "0.6.0", serverVersion: "0.7.0")
        let differentServer = ConnectionHealthKind.outdated(clientVersion: "0.6.0", serverVersion: "0.8.0")

        #expect(a == b)
        #expect(a != differentServer)
        #expect(ConnectionHealthKind.hidden != a)

        guard case let .outdated(client, server) = a else {
            Issue.record("expected .outdated")
            return
        }
        #expect(client == "0.6.0")
        #expect(server == "0.7.0")
    }
}
