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
/// this type owns the ONE Swift switch from `NotificationTarget` to native outcomes. The shade
/// entry point (holding a cold-launch tap's outcome until the tab shell mounts) arrives with
/// `PushCoordinator.didReceive` wiring; the in-app inbox list consumes `outcome(for:)` directly.
/// Mirrors `DeepLinkRouter`.
@Observable
@MainActor
final class PushTapRouter {
    /// THE target switch. `.unknown` degrades to nil-route (open app) with a log, per house rule.
    nonisolated static func outcome(for target: NotificationTarget) -> NotificationTapOutcome {
        switch onEnum(of: target) {
        case .book(let book): return .book(id: book.bookId)
        case .profile(let profile): return .profile(userId: profile.userId)
        // The approvals list is a SECTION of AdminView — AdminInboxDestination is the book-triage
        // inbox. Easy mis-map; the test pins this.
        case .adminInbox: return .adminApprovals
        case .campfire: return .none      // #1065 — no campfire surface yet
        case .none: return .none
        case .unknown:
            Log.error("Unexpected NotificationTarget case")
            return .none
        }
    }
}
