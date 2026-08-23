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
/// shade tap's outcome until the tab shell is mounted (cold-launch taps arrive before MainTabView
/// exists — the state lives on RootView, which outlives the shell). The in-app inbox list consumes
/// `outcome(for:)` directly, never `pending`. Mirrors `DeepLinkRouter`.
@Observable
@MainActor
final class PushTapRouter {
    /// A shade tap's resolved destination, held until `MainTabView`'s consumer appends it.
    /// Last tap wins: a second tap arriving before consumption overwrites the first, deliberately —
    /// the destination the user tapped most recently is the one they asked for.
    private(set) var pending: NotificationTapOutcome?

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

    func consume() { pending = nil }
}
