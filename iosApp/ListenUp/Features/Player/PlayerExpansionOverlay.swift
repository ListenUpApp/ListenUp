import SwiftUI
@preconcurrency import Shared

/// Shared `matchedGeometryEffect` identities for the mini ↔ full player morph.
///
/// The cover art is the one element present in *exactly one* of the two morph
/// branches at any time, so SwiftUI animates it between the mini-bar position
/// and the full-player hero position when `isExpanded` toggles under
/// `withAnimation`.
enum PlayerMorph {
    /// The cover-art geometry that morphs between the mini bar and the full hero.
    static let coverID = "player-cover"
}

/// Hosts the mini player and the full player under a single `@Namespace`, morphing
/// the cover art between them via `matchedGeometryEffect`.
///
/// `tabViewBottomAccessory` and `fullScreenCover` each live in their own
/// presentation context, so a shared-geometry morph can't cross that boundary.
/// This overlay collapses both surfaces into one view tree: tap the mini bar to
/// spring up into the full player; tap the header chevron to spring back down.
/// (Interactive drag gestures land in a follow-up task.)
struct PlayerExpansionOverlay: View {
    let coordinator: PlayerCoordinator

    @Namespace private var namespace
    @State private var isExpanded = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The spring that drives both the cover morph and the chrome transition.
    private var morphAnimation: Animation? {
        reduceMotion ? nil : .spring(response: 0.42, dampingFraction: 0.86)
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            if isExpanded {
                FullScreenPlayerView(
                    observer: coordinator,
                    namespace: namespace,
                    onCollapse: collapse
                )
                .transition(fullPlayerTransition)
                .zIndex(2)
            } else {
                MiniPlayerBar(
                    observer: coordinator,
                    namespace: namespace,
                    onTap: expand
                )
                .padding(.horizontal, 12)
                .padding(.bottom, MiniPlayerBar.tabBarClearance)
                .transition(.opacity)
                .zIndex(1)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
        .animation(morphAnimation, value: isExpanded)
    }

    /// The surrounding full-player chrome slides up from the bottom (fades under
    /// Reduce Motion); the cover itself morphs independently via shared geometry.
    private var fullPlayerTransition: AnyTransition {
        reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity)
    }

    private func expand() {
        withAnimation(morphAnimation) { isExpanded = true }
    }

    private func collapse() {
        withAnimation(morphAnimation) { isExpanded = false }
    }
}
