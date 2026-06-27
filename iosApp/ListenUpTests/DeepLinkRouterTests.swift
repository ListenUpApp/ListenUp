import Testing
@preconcurrency import Shared
@testable import ListenUp

/// Pins that a `ShareTargetInvite` resolves to `.claimInvite` without touching the connected-server
/// resolution — the one router path exercisable without Koin. Book resolution needs `ServerConfig`,
/// a Kotlin interface that can't be implemented (faked) in Swift under Swift Export, so that path is
/// covered by the shared Kotest suite (`ShareTargetResolverTest`) + on-device verification.
@MainActor
@Suite("DeepLinkRouter")
struct DeepLinkRouterTests {

    @Test func inviteTargetMapsToClaimInvite() async throws {
        let manager = DeepLinkManager()
        let router = DeepLinkRouter(deepLinkManager: manager)

        manager.setPendingTarget(target: ShareTargetInvite(serverUrl: "https://lib.example.com", code: "JOIN9"))
        // Let the FlowBridge task deliver the StateFlow emission (one suspension point).
        try await Task.sleep(for: .milliseconds(100))

        #expect(router.outcome == .claimInvite(serverURL: "https://lib.example.com", code: "JOIN9"))
    }
}
