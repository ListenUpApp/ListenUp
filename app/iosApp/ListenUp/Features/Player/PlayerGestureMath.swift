import CoreGraphics

/// Pure gesture-decision math for the player expand/dismiss drags.
///
/// Extracted from `PlayerExpansionOverlay` so the threshold/velocity decisions and
/// the drag-derived fade are unit-testable without a running view tree. SwiftUI's
/// `DragGesture` supplies the raw `translation`/`predictedEndTranslation`; these
/// helpers turn them into the booleans and progress values the overlay applies.
enum PlayerGestureMath {
    /// Distance the player must travel down before a release commits to dismiss.
    static let dismissTranslationThreshold: CGFloat = 120
    /// Predicted-end fling distance (downward) that commits to dismiss regardless
    /// of how far the finger has actually moved — fast flicks dismiss early.
    static let dismissVelocityThreshold: CGFloat = 300

    /// Upward travel that commits the mini-bar swipe to expand.
    static let expandTranslationThreshold: CGFloat = -40
    /// Predicted-end upward fling that expands on a fast flick.
    static let expandVelocityThreshold: CGFloat = -120

    /// Offset at which the dismiss fade reaches full transparency. The scrim and
    /// player chrome fade linearly from `1` at rest to `0` here.
    static let dismissFadeDistance: CGFloat = 320

    /// `true` when a downward drag release should commit to dismissing the player —
    /// either it travelled past the distance threshold, or it's a fast downward fling.
    static func shouldDismiss(translation: CGFloat, predictedEndTranslation: CGFloat) -> Bool {
        translation > dismissTranslationThreshold || predictedEndTranslation > dismissVelocityThreshold
    }

    /// `true` when an upward drag release on the mini bar should commit to expanding —
    /// either a clear upward swipe, or a fast upward fling.
    static func shouldExpand(translation: CGFloat, predictedEndTranslation: CGFloat) -> Bool {
        translation < expandTranslationThreshold || predictedEndTranslation < expandVelocityThreshold
    }

    /// Clamps a raw drag translation to downward-only travel (`0...`). The player
    /// follows the finger only when dragged down; upward drags hold at rest.
    static func downwardOffset(translation: CGFloat) -> CGFloat {
        max(0, translation)
    }

    /// Dismiss progress in `0...1` from the current downward offset. Drives the
    /// scrim/chrome opacity (`1 - progress`) and the subtle shrink.
    static func dismissProgress(offset: CGFloat) -> CGFloat {
        min(1, max(0, offset) / dismissFadeDistance)
    }
}
