import Foundation
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

    /// Covers the full `receive(url:)` → `ShareLinkCodec.decode` → `pendingTarget` → resolve seam
    /// that a delivery modifier (`.onOpenURL`) feeds — the end-to-end path the prior invite fixes
    /// never exercised. URL is the real universal-link shape (query payload per #971) with a
    /// synthetic code, incl. a percent-encoded cleartext-LAN `server` and a non-standard port.
    @Test func receiveDecodesUniversalLinkIntoClaimInvite() async throws {
        let manager = DeepLinkManager()
        let router = DeepLinkRouter(deepLinkManager: manager)

        let url = URL(
            string: "https://link.listenup.audio/o?t=invite&server=http%3A%2F%2F192.168.86.250%3A8080&code=TESTINVITECODE"
        )!
        router.receive(url: url)

        await awaitUntil { router.outcome == .claimInvite(serverURL: "http://192.168.86.250:8080", code: "TESTINVITECODE") }

        #expect(router.outcome == .claimInvite(serverURL: "http://192.168.86.250:8080", code: "TESTINVITECODE"))
    }
}
