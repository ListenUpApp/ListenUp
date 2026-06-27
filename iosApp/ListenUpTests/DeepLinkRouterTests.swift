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
        // FlowBridge delivers the StateFlow emission asynchronously (Kotlin collector → MainActor
        // hop). Poll for the resolved outcome rather than racing a fixed sleep — a saturated CI
        // scheduler can blow past a 100 ms window, which is exactly what flaked here.
        await awaitUntil { router.outcome == .claimInvite(serverURL: "https://lib.example.com", code: "JOIN9") }

        #expect(router.outcome == .claimInvite(serverURL: "https://lib.example.com", code: "JOIN9"))
    }
}
