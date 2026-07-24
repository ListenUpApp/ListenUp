import SwiftUI

/// Reserves bottom space for the floating mini player so a screen's scrollable content
/// isn't clipped behind it.
///
/// `MainTabView` reserves this band on each tab's *root* content, but a root view's
/// `.safeAreaInset` doesn't propagate into screens pushed onto the `NavigationStack`
/// (Settings, Administration, …). Those pushed screens apply this modifier to reserve the
/// same band themselves. The reservation tracks `PlayerCoordinator.isVisible`, so it appears
/// and collapses with the mini player and adds nothing when no book is loaded. The height is
/// the mini bar's own footprint (`barHeight + tabBarClearance`) — one source of truth shared
/// with the tab-root reservation.
private struct MiniPlayerBottomInset: ViewModifier {
    @Environment(\.dependencies) private var deps

    func body(content: Content) -> some View {
        content.safeAreaInset(edge: .bottom) {
            if deps.playerCoordinator.isVisible {
                Color.clear
                    .frame(height: MiniPlayerBar.barHeight + MiniPlayerBar.tabBarClearance)
            }
        }
    }
}

extension View {
    /// Reserves the floating mini player's footprint at the bottom when it's visible, so this
    /// screen's content scrolls clear of it. Apply to pushed screens (Settings, Admin, …) that
    /// don't inherit the tab-root reservation. A no-op while the mini player is hidden.
    func miniPlayerBottomInset() -> some View {
        modifier(MiniPlayerBottomInset())
    }
}
