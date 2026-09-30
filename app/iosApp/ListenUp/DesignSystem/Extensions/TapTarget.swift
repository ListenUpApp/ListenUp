import SwiftUI

/// Apple's minimum hit area, and how far a smaller control has to reach to meet it.
///
/// HIG, Accessibility (Buttons and controls: "44x44 pt" on iOS and iPadOS). A glyph can be drawn
/// smaller than that — a chip's remove mark, a dense row's accessory — as long as the finger has
/// 44pt to land in.
enum TapTarget {
    static let minimum: CGFloat = 44

    /// The outset on each edge that widens a `visualSize`-point control to `minimum`.
    static func outset(forVisualSize visualSize: CGFloat) -> CGFloat {
        max(0, (minimum - visualSize) / 2)
    }
}

extension View {
    /// Widens the hit area of a control drawn at `visualSize` points to the 44pt minimum, without
    /// changing its layout or what is drawn — for dense rows and chips where growing the frame
    /// would move everything around it. Apply to a `Button`'s label, after its frame and background.
    ///
    /// The same small glyphs get the pointer's highlight on iPad — HIG, Pointing devices: "Use
    /// highlight for a small element that has a transparent background" — so a trackpad user can
    /// see which of a dense row's controls the click will land on.
    func minimumTapTarget(visualSize: CGFloat) -> some View {
        contentShape(Rectangle().inset(by: -TapTarget.outset(forVisualSize: visualSize)))
            .hoverEffect(.highlight)
    }
}
