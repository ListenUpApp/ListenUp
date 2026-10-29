import CoreGraphics
import SwiftUI

/// The mini player's swipe up: a trigger that opens the full player, never a drag that follows the finger.
///
/// The system owns the full player's motion (the zoom out of the cover, its interactive swipe down), so a swipe up only
/// decides whether to open it. A clear upward swipe of more than 40pt opens it; so does a flick the system projects
/// past 120pt, however little it moved. Pulling back under both before lifting cancels. The rule is the one the old
/// expansion overlay used (`PlayerGestureMath.shouldExpand`, removed with it in #1509).
enum MiniPlayerSwipe {
    /// Upward travel past which a release opens the player. Negative: up is negative in SwiftUI.
    static let travel: CGFloat = -40
    /// Projected upward travel (`predictedEndTranslation`) past which a flick opens the player.
    static let projected: CGFloat = -120

    /// Whether a released drag opens the player, from its vertical translation and the system's projected end.
    static func opens(translation: CGFloat, predictedEnd: CGFloat) -> Bool {
        translation < travel || predictedEnd < projected
    }

    /// Only the expanded capsule listens. Inline, the accessory shares a row with the minimised tab bar, whose own swipe
    /// expands it, so there it keeps the tap.
    static func isEnabled(placement: TabViewBottomAccessoryPlacement?) -> Bool {
        placement == .expanded
    }
}
