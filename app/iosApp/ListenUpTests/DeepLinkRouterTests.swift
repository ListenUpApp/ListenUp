import Foundation
import Testing
@preconcurrency import Shared
@testable import ListenUp

/// Pins that a `ShareTargetInvite` resolves to `.claimInvite` without touching the connected-server
/// resolution — the one router path exercisable without Koin. Book resolution needs `ServerConfig`,
/// a Kotlin interface that can't be implemented (faked) in Swift under Swift Export, so that path is
/// covered by the shared Kotest suite (`ShareTargetResolverTest`) + on-device verification.
///
/// **Wait causally, never on a clock.** `FlowBridge` delivers the `pendingTarget` emission through
/// a main-*queue* hop out of the Kotlin collector, so the resolve lands only when the main thread
/// has a free moment. Under the full parallel suite it may not have one for tens of seconds: every
/// other `@MainActor` test is queued on the same thread, and CI caught the app process in exactly
/// that state — the media/Now-Playing and cover suites held the main thread solid for ~30 s, with
/// the runtime even reporting a `Hang Risk` priority inversion (a user-interactive thread parked in
/// `dispatch_semaphore_wait` behind Default-QoS work). A wall-clock poll cannot survive that: its
/// deadline expires while the delivery it is waiting for is still queued behind the other suites,
/// and the poll's own 50 Hz wake-ups are themselves main-actor work that crowds the queue further.
/// `awaitObservation` suspends on the `@Observable` mutation instead — it enqueues nothing while it
/// waits, so the delivery gets its slot, and the per-test execution-time allowance (120 s in CI)
/// remains the bound if the resolve genuinely never happens.
@MainActor
@Suite("DeepLinkRouter")
struct DeepLinkRouterTests {

    @Test func inviteTargetMapsToClaimInvite() async throws {
        let manager = DeepLinkManager()
        let router = DeepLinkRouter(deepLinkManager: manager)

        let expected = DeepLinkRouter.Outcome.claimInvite(
            serverURL: "https://lib.example.com",
            code: "JOIN9",
            remoteURL: nil
        )
        manager.setPendingTarget(target: ShareTargetInvite(serverUrl: "https://lib.example.com", code: "JOIN9", remoteUrl: nil))
        await awaitObservation { router.outcome == expected }

        #expect(router.outcome == expected)
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

        let expected = DeepLinkRouter.Outcome.claimInvite(
            serverURL: "http://192.168.86.250:8080",
            code: "TESTINVITECODE",
            remoteURL: nil
        )
        await awaitObservation { router.outcome == expected }

        #expect(router.outcome == expected)
    }

    /// A link that carries an optional `remote=` (WAN) URL surfaces it on the outcome, so the claim
    /// flow can fall back to it when the invitee is off the local network.
    @Test func receiveCarriesTheRemoteURLFromTheLink() async throws {
        let manager = DeepLinkManager()
        let router = DeepLinkRouter(deepLinkManager: manager)

        let url = URL(
            string: "https://link.listenup.audio/o?t=invite&server=http%3A%2F%2F192.168.1.5%3A8080&code=JOIN9&remote=https%3A%2F%2Flib.example.com"
        )!
        router.receive(url: url)

        let expected = DeepLinkRouter.Outcome.claimInvite(
            serverURL: "http://192.168.1.5:8080",
            code: "JOIN9",
            remoteURL: "https://lib.example.com"
        )
        await awaitObservation { router.outcome == expected }
        #expect(router.outcome == expected)
    }
}
