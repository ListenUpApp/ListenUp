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
/// This overlay collapses both surfaces into one view tree: tap or swipe up on the
/// mini bar to spring into the full player; tap the chevron, or interactively swipe
/// the full player's header down, to spring back down. During a dismiss drag the
/// whole player tracks the finger and the cover-tint wash fades with travel; a
/// release past the threshold (or a fast fling) commits, otherwise it springs back.
struct PlayerExpansionOverlay: View {
    let coordinator: PlayerCoordinator
    /// Navigate to the given book's detail screen (pushed onto the active tab's stack).
    var onViewBookDetails: (String) -> Void = { _ in }

    @Namespace private var namespace
    @State private var isExpanded = false
    /// Live downward travel of the dismiss drag (`0` at rest). Drives the player's
    /// follow-the-finger offset plus the derived wash fade and shrink.
    @State private var dragOffset: CGFloat = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    /// The spring that drives both the cover morph and the chrome transition.
    private var morphAnimation: Animation? {
        reduceMotion ? nil : .spring(response: 0.42, dampingFraction: 0.86)
    }

    /// `0...1` dismiss progress derived from the current drag offset.
    private var dismissProgress: CGFloat {
        PlayerGestureMath.dismissProgress(offset: dragOffset)
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            if isExpanded {
                FullScreenPlayerView(
                    observer: coordinator,
                    namespace: namespace,
                    onCollapse: collapse,
                    onViewDetails: viewBookDetails,
                    onDragChanged: handleDragChanged,
                    onDragEnded: handleDragEnded
                )
                .offset(y: reduceMotion ? 0 : dragOffset)
                .scaleEffect(reduceMotion ? 1 : 1 - dismissProgress / 10, anchor: .center)
                .opacity(reduceMotion ? 1 : Double(1 - dismissProgress * 0.5))
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
        // Reset the drag offset inside the same animation so the cover morph runs
        // from the dragged position back to the mini bar without a jump, and a
        // later re-expand always starts from rest.
        withAnimation(morphAnimation) {
            isExpanded = false
            dragOffset = 0
        }
    }

    /// "View Book Details" from the player's ellipsis menu: collapse the player so the
    /// cover morphs back down, then push the book's detail screen onto the active tab.
    /// Collapse runs first so the morph plays before the navigation transition; the push
    /// is a no-op when no book is loaded (never stranded — the menu item just dismisses).
    private func viewBookDetails() {
        collapse()
        if let bookId = coordinator.currentBookId {
            onViewBookDetails(bookId)
        }
    }

    /// Live dismiss-drag update from the player's header. Reduce Motion skips the
    /// interactive follow but still tracks travel so the release decision is honest.
    private func handleDragChanged(_ translation: CGFloat) {
        dragOffset = PlayerGestureMath.downwardOffset(translation: translation)
    }

    /// Release decision: commit to dismiss past the threshold / on a fast fling,
    /// otherwise spring the player back to rest.
    private func handleDragEnded(_ translation: CGFloat, _ predictedEndTranslation: CGFloat) {
        if PlayerGestureMath.shouldDismiss(
            translation: translation,
            predictedEndTranslation: predictedEndTranslation
        ) {
            collapse()
        } else {
            withAnimation(morphAnimation) { dragOffset = 0 }
        }
    }
}
