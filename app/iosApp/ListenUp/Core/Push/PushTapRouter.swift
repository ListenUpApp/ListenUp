import SwiftUI
import Shared

/// Native tap destination — the one Swift-side vocabulary both entry points resolve to.
enum NotificationTapOutcome: Equatable {
    case book(id: String)
    case profile(userId: String)
    case adminApprovals
    case none
}

/// Routes notification taps (system shade AND in-app inbox) to destinations. The DECODE and the
/// target projection live in Kotlin (`PushTapRouting` — the one mapping, shared with Android);
/// this type owns the ONE Swift switch from `NotificationTarget` to native outcomes, and holds a
/// shade tap's outcome until a tab shell claims it (cold-launch taps arrive before MainTabView
/// exists). The in-app inbox list consumes `outcome(for:)` directly, never `pending`.
///
/// **One per process** — `PushCoordinator.shared.tapRouter` — because the notification delegate is
/// one per process. With several iPad windows open, a tap must land in exactly one of them: the one
/// most recently brought to the front, which is the window iOS shows for the tap. Each shell reports
/// when its window becomes active and *claims* the tap; the router hands it to one claimant only.
@Observable
@MainActor
final class PushTapRouter {
    /// A shade tap's resolved destination, held until `MainTabView`'s consumer appends it.
    /// Last tap wins: a second tap arriving before consumption overwrites the first, deliberately —
    /// the destination the user tapped most recently is the one they asked for.
    private(set) var pending: NotificationTapOutcome?

    /// The window most recently brought to the front, which takes the next shade tap.
    private(set) var frontSceneID: UUID?

    /// THE target switch. `.unknown` degrades to nil-route (open app) with a log, per house rule.
    nonisolated static func outcome(for target: NotificationTarget) -> NotificationTapOutcome {
        switch target.sealedType() {
        case .book(let bookType):
            let book = bookType.value
            return .book(id: book.bookId)
        case .profile(let profileType):
            let profile = profileType.value
            return .profile(userId: profile.userId)
        // The approvals list is a SECTION of AdminView — AdminInboxDestination is the book-triage
        // inbox. Easy mis-map; the test pins this.
        case .adminInbox: return .adminApprovals
        case .campfire: return .none      // #1065 — no campfire surface yet
        case .none: return .none
        }
    }

    /// Shade entry point: decode in Kotlin (`PushTapRouting` — null for diagnostics, unknown
    /// future types, and malformed input, all of which mean "just open the app"), switch here,
    /// hold until consumed.
    func handleTap(payloadJson: String?) {
        guard let raw = payloadJson,
              let target = PushTapRouting.shared.targetForPayloadJson(raw: raw)
        else { return }
        let outcome = Self.outcome(for: target)
        if outcome != .none { pending = outcome }
    }

    /// A window's tab shell became active: it is now the one a tap should land in.
    func sceneBecameActive(_ sceneID: UUID) { frontSceneID = sceneID }

    /// A window's tab shell went away; if it was the front one, the next claimant may take taps.
    func sceneWentAway(_ sceneID: UUID) {
        if frontSceneID == sceneID { frontSceneID = nil }
    }

    /// Hands the held tap to `sceneID` — and clears it — when that window is the one to show it.
    /// Every other window gets nil, so one tap never pushes in two windows.
    func claimPending(for sceneID: UUID) -> NotificationTapOutcome? {
        guard let pending, Self.routesTap(to: sceneID, frontSceneID: frontSceneID) else { return nil }
        self.pending = nil
        return pending
    }

    /// THE routing decision: the front window takes the tap; before any window has reported (a cold
    /// launch), the first shell to claim it does.
    nonisolated static func routesTap(to sceneID: UUID, frontSceneID: UUID?) -> Bool {
        frontSceneID == nil || frontSceneID == sceneID
    }
}
