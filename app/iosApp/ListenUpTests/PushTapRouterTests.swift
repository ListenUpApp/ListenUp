import Foundation
import Testing
@preconcurrency import Shared
@testable import ListenUp

/// Pins `PushTapRouter`'s single target switch (the `NotificationRowModelTests` pattern — bridged
/// Kotlin values built directly, no Koin) and the shade entry point: a real payload JSON decoded
/// through the Kotlin `PushTapRouting` seam lands in `pending`, malformed input stays put.
///
/// Data-object targets (`AdminInbox`, `None`) export with no `.shared` accessor, so those cases
/// obtain their instance through Kotlin paths instead: the event's own `target` projection —
/// which is also exactly what the in-app inbox consumes, so the pin covers the real read.
@Suite("PushTapRouter")
struct PushTapRouterTests {

    @Test func bookTargetRoutesToBookDetail() {
        #expect(PushTapRouter.outcome(for: NotificationTargetBook(bookId: "b1")) == .book(id: "b1"))
    }

    @Test func profileTargetRoutesToProfile() {
        #expect(PushTapRouter.outcome(for: NotificationTargetProfile(userId: "u1")) == .profile(userId: "u1"))
    }

    /// Pins the AdminDestination choice — AdminInboxDestination is the BOOK inbox, not approvals.
    @Test func adminInboxTargetRoutesToAdminApprovals() {
        let target = NotificationEventRegistrationApproval(userId: "u1").target
        #expect(PushTapRouter.outcome(for: target) == .adminApprovals)
    }

    @Test func campfireTargetStaysPut() {
        // #1065 — no campfire surface yet.
        #expect(PushTapRouter.outcome(for: NotificationTargetCampfire(campfireId: "c1")) == NotificationTapOutcome.none)
    }

    @Test func noneTargetStaysPut() {
        let target = NotificationEventRegistrationDecision(userId: "u1", approved: true).target
        #expect(PushTapRouter.outcome(for: target) == NotificationTapOutcome.none)
    }

    /// End-to-end shade path: the wire-shaped payload JSON (the Kotlin `PushTapRoutingTest` pins
    /// the decode itself) resolves through `PushTapRouting` and is held in `pending`.
    @MainActor
    @Test func handleTapHoldsARegistrationApprovalPayload() {
        let router = PushTapRouter()

        router.handleTap(payloadJson: #"{"type":"registration_approval","userId":"u1"}"#)

        #expect(router.pending == .adminApprovals)
        router.consume()
        #expect(router.pending == nil)
    }

    @MainActor
    @Test func handleTapIgnoresMalformedPayload() {
        let router = PushTapRouter()

        router.handleTap(payloadJson: "not json")
        router.handleTap(payloadJson: nil)

        #expect(router.pending == nil)
    }
}
