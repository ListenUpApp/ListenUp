import SwiftUI
import UIKit
import Shared

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
    /// The tab content's live bottom safe-area inset (home indicator + the floating,
    /// minimize-aware tab bar). The mini player clears exactly this much so it tracks
    /// the bar in both its full and minimized states instead of guessing with a fixed gap.
    var tabContentBottomInset: CGFloat = 0
    /// Navigate to the given book's detail screen (pushed onto the active tab's stack).
    var onViewBookDetails: (String) -> Void = { _ in }
    /// Navigate to the given series (pushed onto the active tab's stack).
    var onViewSeries: (String) -> Void = { _ in }
    /// Navigate to the given contributor — author or narrator (pushed onto the active tab's stack).
    var onViewContributor: (String) -> Void = { _ in }

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
                    onViewSeries: { seriesId in collapse(); onViewSeries(seriesId) },
                    onViewContributor: { contributorId in collapse(); onViewContributor(contributorId) },
                    onDragChanged: handleDragChanged,
                    onDragEnded: handleDragEnded
                )
                .offset(y: reduceMotion ? 0 : dragOffset)
                .scaleEffect(reduceMotion ? 1 : 1 - dismissProgress / 10, anchor: .center)
                // No per-frame `.opacity` fade on the player during the drag: the player's
                // background is a system glass material, and fading a material to a changing
                // alpha re-composites it against the live content behind it every frame. On a
                // slow continuous drag that lands on every intermediate alpha → constant
                // flicker (a fast swipe blows through it too quickly to see). The glass already
                // supplies the translucency the dismiss wants; the offset + slight shrink carry
                // the gesture. Fade stays only on the cover-morph spring, which is steady.
                .transition(fullPlayerTransition)
                .zIndex(2)
            } else {
                MiniPlayerBar(
                    observer: coordinator,
                    namespace: namespace,
                    onTap: expand
                )
                .padding(.horizontal, 12)
                // Clear the real (measured) tab bar + home indicator, plus a small gap.
                // Tracks the `.onScrollDown` minimize state, so it never overlaps the
                // bar nor floats with a gap. We own the full bottom inset here because
                // the ZStack ignores its own bottom safe area (below).
                .padding(.bottom, tabContentBottomInset + MiniPlayerBar.tabBarClearance)
                .transition(.opacity)
                .zIndex(1)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
        // Anchor to the true screen bottom (the mini player supplies its own inset),
        // and stay put when the keyboard appears — keyboard avoidance otherwise lifts
        // the whole player with the keyboard, leaving the expanded player off-position
        // and undismissable (soft-lock).
        .ignoresSafeArea(.container, edges: .bottom)
        .ignoresSafeArea(.keyboard, edges: .bottom)
        .animation(morphAnimation, value: isExpanded)
    }

    /// The surrounding full-player chrome slides up from the bottom (fades under
    /// Reduce Motion); the cover itself morphs independently via shared geometry.
    private var fullPlayerTransition: AnyTransition {
        reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity)
    }

    private func expand() {
        // Resign any active first responder so tapping the mini player while typing
        // brings up a correctly-positioned full player (the keyboard would otherwise
        // linger and re-trigger avoidance once it dismisses).
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil
        )
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
